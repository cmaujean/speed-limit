import 'dart:io';

import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';

import 'services/overlay_service.dart';

void main() {
  runApp(const SpeedLimitApp());
}

class SpeedLimitApp extends StatelessWidget {
  const SpeedLimitApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Speed Limit',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: Colors.red,
        useMaterial3: true,
        brightness: Brightness.dark,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  bool _bubbleActive = false;
  bool _hasOverlayPermission = false;
  bool _hasLocationPermission = false;
  int _overLimitAllowance = 5;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkPermissions();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkPermissions();
    }
  }

  Future<void> _checkPermissions() async {
    if (!Platform.isAndroid) return;
    final overlay = await OverlayService.checkPermission();
    final locPerm = await Geolocator.checkPermission();
    final locOk = locPerm == LocationPermission.always ||
        locPerm == LocationPermission.whileInUse;
    setState(() {
      _hasOverlayPermission = overlay;
      _hasLocationPermission = locOk;
    });
  }

  Future<void> _requestLocationPermission() async {
    var perm = await Geolocator.checkPermission();
    if (perm == LocationPermission.denied) {
      perm = await Geolocator.requestPermission();
    }
    setState(() {
      _hasLocationPermission = perm == LocationPermission.always ||
          perm == LocationPermission.whileInUse;
    });
  }

  Future<void> _toggleBubble() async {
    if (!_hasOverlayPermission) {
      await OverlayService.requestPermission();
      return;
    }
    if (!_hasLocationPermission) {
      await _requestLocationPermission();
      return;
    }

    if (_bubbleActive) {
      await OverlayService.hide();
      setState(() => _bubbleActive = false);
    } else {
      final shown = await OverlayService.show(
        overLimitAllowance: _overLimitAllowance,
      );
      setState(() => _bubbleActive = shown);
    }
  }

  void _setAllowance(int value) {
    setState(() => _overLimitAllowance = value);
    if (_bubbleActive) {
      OverlayService.updateAllowance(overLimitAllowance: value);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 32),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // App icon / title
                Container(
                  width: 100,
                  height: 100,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: Colors.white,
                    border: Border.all(color: Colors.red[700]!, width: 8),
                  ),
                  child: const Center(
                    child: Icon(Icons.speed, size: 48, color: Colors.black87),
                  ),
                ),
                const SizedBox(height: 24),
                Text(
                  'Speed Limit',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                ),
                const SizedBox(height: 8),
                Text(
                  _bubbleActive
                      ? 'Bubble is active'
                      : 'Launch the bubble to see speed limits',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Colors.white54,
                      ),
                ),
                const SizedBox(height: 48),

                // Launch / stop bubble
                SizedBox(
                  width: double.infinity,
                  height: 56,
                  child: FilledButton.icon(
                    onPressed: _toggleBubble,
                    icon: Icon(_bubbleActive ? Icons.stop : Icons.play_arrow),
                    label: Text(
                      _bubbleActive ? 'Stop Bubble' : 'Launch Bubble',
                      style: const TextStyle(fontSize: 18),
                    ),
                    style: FilledButton.styleFrom(
                      backgroundColor:
                          _bubbleActive ? Colors.red[700] : Colors.green[700],
                    ),
                  ),
                ),
                const SizedBox(height: 40),

                // Settings section
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    'Settings',
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                          color: Colors.white38,
                          letterSpacing: 1.2,
                        ),
                  ),
                ),
                const SizedBox(height: 12),
                Container(
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.06),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  padding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          'Over-limit allowance',
                          style:
                              Theme.of(context).textTheme.bodyMedium?.copyWith(
                                    color: Colors.white70,
                                  ),
                        ),
                      ),
                      IconButton(
                        onPressed: _overLimitAllowance > 0
                            ? () => _setAllowance(_overLimitAllowance - 1)
                            : null,
                        icon: const Icon(Icons.remove_circle_outline),
                      ),
                      Text(
                        '$_overLimitAllowance mph',
                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              color: Colors.white,
                              fontWeight: FontWeight.bold,
                            ),
                      ),
                      IconButton(
                        onPressed: () =>
                            _setAllowance(_overLimitAllowance + 1),
                        icon: const Icon(Icons.add_circle_outline),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 24),

                // Permission status
                if (!_hasOverlayPermission)
                  _PermissionRow(
                    label: 'Overlay permission required',
                    onTap: () => OverlayService.requestPermission(),
                  ),
                if (!_hasLocationPermission)
                  _PermissionRow(
                    label: 'Location permission required',
                    onTap: _requestLocationPermission,
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PermissionRow extends StatelessWidget {
  final String label;
  final VoidCallback onTap;

  const _PermissionRow({required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: TextButton.icon(
        onPressed: onTap,
        icon: const Icon(Icons.warning_amber, size: 18),
        label: Text(label),
        style: TextButton.styleFrom(foregroundColor: Colors.orangeAccent),
      ),
    );
  }
}
