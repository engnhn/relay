use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use futures_util::{SinkExt, StreamExt};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use notify_rust::Notification;
use rcgen::{CertifiedKey, generate_simple_self_signed};
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::env;
use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream};
use tokio::signal;
use tokio_rustls::TlsAcceptor;
use tokio_rustls::rustls::ServerConfig;
use tokio_rustls::rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use tokio_tungstenite::accept_async;
use tokio_tungstenite::tungstenite::Message;
use tracing::{error, info, warn};

const APP_NAME: &str = "relay";
const DEFAULT_HOST: &str = "0.0.0.0";
const DEFAULT_PORT: u16 = 9876;
const NOTIFICATION_CREATED: &str = "notification.created";
const RELAY_HELLO: &str = "relay.hello";
const RELAY_READY: &str = "relay.ready";
const RELAY_UNAUTHORIZED: &str = "relay.unauthorized";
const SERVICE_TYPE: &str = "_relay._tcp.local.";
const SERVICE_INSTANCE: &str = "relay";
const CERT_FILE: &str = "cert.der";
const KEY_FILE: &str = "key.der";

#[derive(Parser)]
#[command(
    name = APP_NAME,
    version,
    about = "forward android notifications to linux"
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    Listen {
        #[arg(long, env = "RELAY_HOST", default_value = DEFAULT_HOST)]
        host: String,
        #[arg(long, env = "RELAY_PORT", default_value_t = DEFAULT_PORT)]
        port: u16,
        #[arg(long, env = "RELAY_TOKEN")]
        token: Option<String>,
        #[arg(long, env = "RELAY_ALLOW_EMPTY_TOKEN")]
        allow_empty_token: bool,
        #[arg(long, env = "RELAY_IDENTITY_DIR")]
        identity_dir: Option<PathBuf>,
        #[arg(long, env = "RELAY_NO_DISCOVERY")]
        no_discovery: bool,
    },
}

#[derive(Debug, Deserialize)]
struct IncomingEnvelope {
    #[serde(rename = "type")]
    message_type: String,
    token: Option<String>,
}

#[derive(Debug, Deserialize)]
struct IncomingNotification {
    #[serde(rename = "type")]
    message_type: String,
    package: String,
    app: Option<String>,
    title: Option<String>,
    body: Option<String>,
    timestamp: Option<i64>,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_target(false)
        .without_time()
        .init();

    let cli = Cli::parse();

    match cli.command {
        Command::Listen {
            host,
            port,
            token,
            allow_empty_token,
            identity_dir,
            no_discovery,
        } => {
            let token = token.and_then(|value| clean_text(Some(&value)));
            if token.is_none() && !allow_empty_token {
                anyhow::bail!(
                    "RELAY_TOKEN or --token is required; use --allow-empty-token only for local testing"
                );
            }
            listen(&host, port, token, identity_dir, !no_discovery).await
        }
    }
}

async fn listen(
    host: &str,
    port: u16,
    token: Option<String>,
    identity_dir: Option<PathBuf>,
    discovery: bool,
) -> Result<()> {
    let addr = format!("{host}:{port}");
    let listener = TcpListener::bind(&addr)
        .await
        .with_context(|| format!("failed to bind {addr}"))?;
    let auth = AuthConfig::new(token);
    let identity = ServerIdentity::load_or_create(identity_dir)?;
    let tls_acceptor = identity.tls_acceptor()?;
    let _discovery = if discovery {
        Some(DiscoveryAdvertisement::start(port)?)
    } else {
        None
    };

    info!("relay listening on wss://{addr}");
    info!(fingerprint = %identity.fingerprint, "receiver identity fingerprint");
    if auth.is_required() {
        info!("token authentication enabled");
    } else {
        warn!("token authentication disabled for local testing");
    }

    loop {
        tokio::select! {
            accept_result = listener.accept() => {
                match accept_result {
                    Ok((stream, peer)) => {
                        info!(%peer, "client connected");
                        let auth = auth.clone();
                        let tls_acceptor = tls_acceptor.clone();
                        tokio::spawn(async move {
                            if let Err(err) = handle_client(stream, auth, tls_acceptor).await {
                                warn!(%peer, error = %err, "client error");
                            }
                            info!(%peer, "client disconnected");
                        });
                    }
                    Err(err) => warn!(error = %err, "accept error"),
                }
            }
            shutdown_result = signal::ctrl_c() => {
                shutdown_result.context("failed to wait for shutdown signal")?;
                info!("shutdown signal received");
                break;
            }
        }
    }

    Ok(())
}

struct ServerIdentity {
    cert: CertificateDer<'static>,
    key: PrivatePkcs8KeyDer<'static>,
    fingerprint: String,
}

impl ServerIdentity {
    fn load_or_create(identity_dir: Option<PathBuf>) -> Result<Self> {
        let dir = identity_dir.unwrap_or_else(default_identity_dir);
        fs::create_dir_all(&dir)
            .with_context(|| format!("failed to create identity dir {}", dir.display()))?;
        let cert_path = dir.join(CERT_FILE);
        let key_path = dir.join(KEY_FILE);

        if !cert_path.exists() || !key_path.exists() {
            let CertifiedKey { cert, signing_key } =
                generate_simple_self_signed(vec!["relay.local".to_string()])
                    .context("failed to generate receiver identity")?;
            fs::write(&cert_path, cert.der().as_ref())
                .with_context(|| format!("failed to write {}", cert_path.display()))?;
            fs::write(&key_path, signing_key.serialize_der())
                .with_context(|| format!("failed to write {}", key_path.display()))?;
        }

        let cert_bytes = fs::read(&cert_path)
            .with_context(|| format!("failed to read {}", cert_path.display()))?;
        let key_bytes = fs::read(&key_path)
            .with_context(|| format!("failed to read {}", key_path.display()))?;
        let fingerprint = certificate_fingerprint(&cert_bytes);

        Ok(Self {
            cert: CertificateDer::from(cert_bytes),
            key: PrivatePkcs8KeyDer::from(key_bytes),
            fingerprint,
        })
    }

    fn tls_acceptor(&self) -> Result<TlsAcceptor> {
        let config = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(
                vec![self.cert.clone()],
                PrivateKeyDer::Pkcs8(self.key.clone_key()),
            )
            .context("failed to build tls server config")?;

        Ok(TlsAcceptor::from(Arc::new(config)))
    }
}

fn default_identity_dir() -> PathBuf {
    env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".config")
        .join(APP_NAME)
}

fn certificate_fingerprint(cert: &[u8]) -> String {
    let digest = Sha256::digest(cert);
    hex::encode(digest).to_uppercase()
}

struct DiscoveryAdvertisement {
    daemon: ServiceDaemon,
    fullname: String,
}

impl DiscoveryAdvertisement {
    fn start(port: u16) -> Result<Self> {
        let daemon = ServiceDaemon::new().context("failed to start mdns discovery daemon")?;
        let properties = [("protocol", "websocket"), ("app", APP_NAME)];
        let service = ServiceInfo::new(
            SERVICE_TYPE,
            SERVICE_INSTANCE,
            "relay.local.",
            "",
            port,
            &properties[..],
        )
        .context("failed to configure mdns discovery service")?
        .enable_addr_auto();
        let fullname = service.get_fullname().to_string();

        daemon
            .register(service)
            .context("failed to advertise relay over mdns")?;
        info!(service_type = SERVICE_TYPE, "mdns discovery enabled");

        Ok(Self { daemon, fullname })
    }
}

impl Drop for DiscoveryAdvertisement {
    fn drop(&mut self) {
        let _ = self.daemon.unregister(&self.fullname);
    }
}

async fn handle_client(
    stream: TcpStream,
    auth: AuthConfig,
    tls_acceptor: TlsAcceptor,
) -> Result<()> {
    let tls_stream = tls_acceptor
        .accept(stream)
        .await
        .context("failed to accept tls connection")?;
    let mut socket = accept_async(tls_stream)
        .await
        .context("failed to accept websocket connection")?;

    while let Some(message) = socket.next().await {
        match message {
            Ok(Message::Text(text)) => match handle_text_message(&text, &auth) {
                ClientMessageResult::Continue => {}
                ClientMessageResult::Ready => {
                    socket
                        .send(Message::Text(
                            json!({ "type": RELAY_READY }).to_string().into(),
                        ))
                        .await
                        .context("failed to send ready response")?;
                }
                ClientMessageResult::Unauthorized => {
                    socket
                        .send(Message::Text(
                            json!({ "type": RELAY_UNAUTHORIZED }).to_string().into(),
                        ))
                        .await
                        .context("failed to send unauthorized response")?;
                    let _ = socket.close(None).await;
                    break;
                }
            },
            Ok(Message::Binary(_)) => warn!("ignored binary websocket message"),
            Ok(Message::Close(_)) => break,
            Ok(_) => {}
            Err(err) => {
                warn!(error = %err, "websocket read error");
                break;
            }
        }
    }

    Ok(())
}

enum ClientMessageResult {
    Continue,
    Ready,
    Unauthorized,
}

fn handle_text_message(text: &str, auth: &AuthConfig) -> ClientMessageResult {
    let envelope = match serde_json::from_str::<IncomingEnvelope>(text) {
        Ok(envelope) => envelope,
        Err(err) => {
            warn!(error = %err, "ignored malformed message");
            return ClientMessageResult::Continue;
        }
    };

    if !auth.allows(envelope.token.as_deref()) {
        warn!("ignored message with invalid token");
        return ClientMessageResult::Unauthorized;
    }

    if envelope.message_type == RELAY_HELLO {
        return ClientMessageResult::Ready;
    }

    match serde_json::from_str::<IncomingNotification>(text) {
        Ok(notification) => {
            if !has_supported_message_type(&notification) {
                warn!(
                    message_type = %notification.message_type,
                    "ignored unsupported message type"
                );
                return ClientMessageResult::Continue;
            }

            if !has_package_name(&notification) {
                warn!("ignored notification without package name");
                return ClientMessageResult::Continue;
            }

            if let Err(err) = show_notification(&notification) {
                error!(error = %err, "failed to show notification");
            }
        }
        Err(err) => warn!(error = %err, "ignored malformed message"),
    }

    ClientMessageResult::Continue
}

#[derive(Clone, Debug)]
struct AuthConfig {
    token: Option<String>,
}

impl AuthConfig {
    fn new(token: Option<String>) -> Self {
        Self { token }
    }

    fn is_required(&self) -> bool {
        self.token.is_some()
    }

    fn allows(&self, incoming: Option<&str>) -> bool {
        match self.token.as_deref() {
            Some(expected) => incoming == Some(expected),
            None => true,
        }
    }
}

fn has_supported_message_type(notification: &IncomingNotification) -> bool {
    notification.message_type == NOTIFICATION_CREATED
}

fn has_package_name(notification: &IncomingNotification) -> bool {
    !notification.package.trim().is_empty()
}

fn show_notification(notification: &IncomingNotification) -> Result<()> {
    let summary = clean_text(notification.title.as_deref())
        .or_else(|| clean_text(notification.app.as_deref()))
        .unwrap_or_else(|| notification.package.clone());
    let body = clean_text(notification.body.as_deref()).unwrap_or_default();
    let appname = clean_text(notification.app.as_deref()).unwrap_or_else(|| APP_NAME.to_string());

    info!(
        app = %appname,
        package = %notification.package,
        title = %summary,
        has_body = !body.is_empty(),
        "notification received"
    );

    let mut desktop_notification = Notification::new();
    desktop_notification
        .appname(&appname)
        .summary(&summary)
        .body(&body);

    desktop_notification
        .show()
        .context("freedesktop notification call failed")?;

    let _ = notification.timestamp;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn notification(message_type: &str, package: &str) -> IncomingNotification {
        IncomingNotification {
            message_type: message_type.to_string(),
            package: package.to_string(),
            app: None,
            title: None,
            body: None,
            timestamp: None,
        }
    }

    #[test]
    fn clean_text_trims_non_empty_values() {
        assert_eq!(clean_text(Some("  hello  ")), Some("hello".to_string()));
    }

    #[test]
    fn clean_text_rejects_empty_values() {
        assert_eq!(clean_text(None), None);
        assert_eq!(clean_text(Some("   ")), None);
    }

    #[test]
    fn recognizes_supported_notification_event() {
        assert!(has_supported_message_type(&notification(
            NOTIFICATION_CREATED,
            "com.example"
        )));
        assert!(!has_supported_message_type(&notification(
            "notification.deleted",
            "com.example"
        )));
    }

    #[test]
    fn recognizes_required_package_name() {
        assert!(has_package_name(&notification(
            NOTIFICATION_CREATED,
            "com.example"
        )));
        assert!(!has_package_name(&notification(
            NOTIFICATION_CREATED,
            "   "
        )));
    }

    #[test]
    fn allows_messages_when_token_is_not_configured() {
        let auth = AuthConfig::new(None);

        assert!(auth.allows(None));
        assert!(auth.allows(Some("anything")));
    }

    #[test]
    fn rejects_messages_without_matching_token_when_configured() {
        let auth = AuthConfig::new(Some("secret".to_string()));

        assert!(auth.allows(Some("secret")));
        assert!(!auth.allows(None));
        assert!(!auth.allows(Some("wrong")));
    }

    #[test]
    fn formats_certificate_fingerprint_as_uppercase_sha256_hex() {
        let fingerprint = certificate_fingerprint(b"cert");

        assert_eq!(fingerprint.len(), 64);
        assert!(fingerprint.chars().all(|ch| ch.is_ascii_hexdigit()));
        assert_eq!(fingerprint, fingerprint.to_uppercase());
    }
}

fn clean_text(value: Option<&str>) -> Option<String> {
    let trimmed = value?.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_string())
    }
}
