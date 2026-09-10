import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _appName = 'relay';
const _settingsChannelName = 'relay/settings';
const _statusChannelName = 'relay/status';
const _defaultPort = 9876;
const _connectedStatus = 'connected';
const _connectingStatus = 'connecting';
const _disconnectedStatus = 'disconnected';
const _connectionErrorPrefix = 'connection error';

void main() {
  runApp(const RelayApp());
}

class RelayApp extends StatelessWidget {
  const RelayApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: _appName,
      theme: ThemeData(
        colorScheme: const ColorScheme.light(
          primary: Colors.black,
          onPrimary: Colors.white,
          surface: Colors.white,
          onSurface: Colors.black,
        ),
        scaffoldBackgroundColor: Colors.white,
        appBarTheme: const AppBarTheme(
          backgroundColor: Colors.white,
          foregroundColor: Colors.black,
          elevation: 0,
        ),
        inputDecorationTheme: const InputDecorationTheme(
          border: OutlineInputBorder(),
          focusedBorder: OutlineInputBorder(
            borderSide: BorderSide(color: Colors.black, width: 2),
          ),
        ),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            backgroundColor: Colors.black,
            foregroundColor: Colors.white,
          ),
        ),
        outlinedButtonTheme: OutlinedButtonThemeData(
          style: OutlinedButton.styleFrom(
            foregroundColor: Colors.black,
            side: const BorderSide(color: Colors.black),
          ),
        ),
      ),
      home: const RelayHome(),
    );
  }
}

class RelayHome extends StatefulWidget {
  const RelayHome({super.key});

  @override
  State<RelayHome> createState() => _RelayHomeState();
}

class _RelayHomeState extends State<RelayHome> {
  static const _methods = MethodChannel(_settingsChannelName);
  static const _statusEvents = EventChannel(_statusChannelName);

  final _hostController = TextEditingController();
  final _portController = TextEditingController(text: '$_defaultPort');
  final _tokenController = TextEditingController();
  final _fingerprintController = TextEditingController();

  StreamSubscription<dynamic>? _statusSubscription;
  String _status = _disconnectedStatus;
  bool _connected = false;

  @override
  void initState() {
    super.initState();
    _statusSubscription = _statusEvents.receiveBroadcastStream().listen(
      (event) => _setConnected(event == _connectedStatus, event.toString()),
      onError: (Object error) => _setStatus('$_connectionErrorPrefix: $error'),
    );
    unawaited(_bootstrap());
  }

  Future<void> _bootstrap() async {
    await _loadSavedConnection();
    await _discoverReceiver(
      autoConnect: _fingerprintController.text.isNotEmpty,
    );
  }

  Future<void> _loadSavedConnection() async {
    final saved = await _methods.invokeMapMethod<String, dynamic>(
      'getConnection',
    );
    if (!mounted || saved == null) {
      return;
    }

    _hostController.text = saved['host']?.toString() ?? '';
    _portController.text = saved['port']?.toString() ?? '$_defaultPort';
    _tokenController.text = saved['token']?.toString() ?? '';
    _fingerprintController.text = saved['fingerprint']?.toString() ?? '';
  }

  Future<void> _connect() async {
    final host = _hostController.text.trim();
    final port = int.tryParse(_portController.text.trim());
    final token = _tokenController.text.trim();
    final fingerprint = _fingerprintController.text.trim();
    if (host.isEmpty || port == null) {
      _setStatus('enter a valid ip address and port');
      return;
    }
    if (fingerprint.isEmpty) {
      _setStatus('enter the receiver fingerprint');
      return;
    }

    _setStatus(_connectingStatus);

    try {
      await _methods.invokeMethod<void>('connect', <String, Object>{
        'host': host,
        'port': port,
        'token': token,
        'fingerprint': fingerprint,
      });
    } on Object catch (error) {
      _setStatus('connection failed: $error');
    }
  }

  Future<void> _discoverReceiver({bool autoConnect = false}) async {
    try {
      final found = await _methods.invokeMapMethod<String, dynamic>('discover');
      if (!mounted || found == null) {
        return;
      }

      _hostController.text = found['host']?.toString() ?? '';
      _portController.text = found['port']?.toString() ?? '$_defaultPort';

      if ((autoConnect || !_connected) &&
          _fingerprintController.text.isNotEmpty) {
        await _connect();
      }
    } on Object catch (error) {
      _setStatus('discovery failed: $error');
    }
  }

  Future<void> _openNotificationSettings() async {
    try {
      await _methods.invokeMethod<void>('openNotificationSettings');
    } on PlatformException catch (error) {
      _setStatus('could not open settings: ${error.message ?? error.code}');
    }
  }

  void _setConnected(bool connected, String status) {
    if (!mounted) {
      return;
    }

    setState(() {
      _connected = connected;
      _status = status;
    });
  }

  void _setStatus(String status) {
    if (!mounted) {
      return;
    }

    setState(() {
      _status = status;
    });
  }

  @override
  void dispose() {
    _hostController.dispose();
    _portController.dispose();
    _tokenController.dispose();
    _fingerprintController.dispose();
    _statusSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text(_appName)),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              _appName,
              style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 4),
            const Text('android notifications to linux'),
            const SizedBox(height: 24),
            TextField(
              controller: _hostController,
              keyboardType: TextInputType.url,
              decoration: const InputDecoration(
                labelText: 'linux machine ip address',
                hintText: '192.168.1.20',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _portController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'port'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _tokenController,
              obscureText: true,
              decoration: const InputDecoration(
                labelText: 'token',
                hintText: 'shared secret',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _fingerprintController,
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(
                labelText: 'receiver fingerprint',
                hintText: 'SHA-256 from receiver log',
              ),
            ),
            const SizedBox(height: 20),
            Text('status: $_status'),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: _connect,
              child: Text(_connected ? 'reconnect' : 'connect'),
            ),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: _discoverReceiver,
              child: const Text('discover receiver'),
            ),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: _openNotificationSettings,
              child: const Text('open notification access settings'),
            ),
          ],
        ),
      ),
    );
  }
}
