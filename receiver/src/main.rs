use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use futures_util::StreamExt;
use notify_rust::Notification;
use serde::Deserialize;
use tokio::net::{TcpListener, TcpStream};
use tokio::signal;
use tokio_tungstenite::accept_async;
use tokio_tungstenite::tungstenite::Message;
use tracing::{error, info, warn};

const APP_NAME: &str = "relay";
const DEFAULT_HOST: &str = "0.0.0.0";
const DEFAULT_PORT: u16 = 9876;
const NOTIFICATION_CREATED: &str = "notification.created";

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
    },
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
        Command::Listen { host, port } => listen(&host, port).await,
    }
}

async fn listen(host: &str, port: u16) -> Result<()> {
    let addr = format!("{host}:{port}");
    let listener = TcpListener::bind(&addr)
        .await
        .with_context(|| format!("failed to bind {addr}"))?;

    info!("relay listening on ws://{addr}");

    loop {
        tokio::select! {
            accept_result = listener.accept() => {
                match accept_result {
                    Ok((stream, peer)) => {
                        info!(%peer, "client connected");
                        tokio::spawn(async move {
                            if let Err(err) = handle_client(stream).await {
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

async fn handle_client(stream: TcpStream) -> Result<()> {
    let mut socket = accept_async(stream)
        .await
        .context("failed to accept websocket connection")?;

    while let Some(message) = socket.next().await {
        match message {
            Ok(Message::Text(text)) => handle_text_message(&text),
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

fn handle_text_message(text: &str) {
    match serde_json::from_str::<IncomingNotification>(text) {
        Ok(notification) => {
            if !has_supported_message_type(&notification) {
                warn!(
                    message_type = %notification.message_type,
                    "ignored unsupported message type"
                );
                return;
            }

            if !has_package_name(&notification) {
                warn!("ignored notification without package name");
                return;
            }

            if let Err(err) = show_notification(&notification) {
                error!(error = %err, "failed to show notification");
            }
        }
        Err(err) => warn!(error = %err, "ignored malformed message"),
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
}

fn clean_text(value: Option<&str>) -> Option<String> {
    let trimmed = value?.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_string())
    }
}
