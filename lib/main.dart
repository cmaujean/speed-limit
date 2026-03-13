import 'dart:io';

import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:url_launcher/url_launcher.dart';

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

class _HomePageState extends State<HomePage>
    with WidgetsBindingObserver, TickerProviderStateMixin {
  bool _bubbleActive = false;
  bool _hasOverlayPermission = false;
  bool _hasLocationPermission = false;
  int _overLimitAllowance = 5;

  late AnimationController _coffeeController;
  late Animation<double> _coffeeScale;
  late Animation<double> _coffeeOpacity;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkPermissions();

    _coffeeController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 3),
    );

    // Pulse scale: 1.0 -> 1.3 -> 1.0, repeating ~4 times over 3s
    _coffeeScale = TweenSequence<double>([
      TweenSequenceItem(tween: Tween(begin: 1.0, end: 1.3), weight: 1),
      TweenSequenceItem(tween: Tween(begin: 1.3, end: 1.0), weight: 1),
    ]).animate(CurvedAnimation(
      parent: _coffeeController,
      curve: Curves.easeInOut,
    ));

    // Fade opacity to pulse the color
    _coffeeOpacity = TweenSequence<double>([
      TweenSequenceItem(tween: Tween(begin: 1.0, end: 0.5), weight: 1),
      TweenSequenceItem(tween: Tween(begin: 0.5, end: 1.0), weight: 1),
    ]).animate(CurvedAnimation(
      parent: _coffeeController,
      curve: Curves.easeInOut,
    ));

    _coffeeController.repeat();
    Future.delayed(const Duration(seconds: 3), () {
      if (mounted) _coffeeController.stop();
      if (mounted) _coffeeController.value = 0.0;
    });
  }

  @override
  void dispose() {
    _coffeeController.dispose();
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
              children: [
                const Spacer(),
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
                const SizedBox(height: 16),
                Text(
                  _bubbleActive
                      ? 'Bubble is running. You can close this screen.'
                      : 'Tap Launch Bubble to start.\nYou can close this screen after.',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Colors.white54,
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

                const Spacer(),

                // Buy me a coffee
                AnimatedBuilder(
                  animation: _coffeeController,
                  builder: (context, child) {
                    final scale = _coffeeController.isAnimating
                        ? _coffeeScale.value
                        : 1.0;
                    final opacity = _coffeeController.isAnimating
                        ? _coffeeOpacity.value
                        : 1.0;
                    return Transform.scale(
                      scale: scale,
                      child: Opacity(opacity: opacity, child: child),
                    );
                  },
                  child: TextButton.icon(
                    onPressed: () => launchUrl(
                      Uri.parse('https://buymeacoffee.com/4dvu1r9nsh'),
                      mode: LaunchMode.externalApplication,
                    ),
                    icon: const Icon(Icons.coffee, size: 18),
                    label: const Text('Buy me a coffee'),
                    style: TextButton.styleFrom(foregroundColor: Colors.amber),
                  ),
                ),
                const SizedBox(height: 16),
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
