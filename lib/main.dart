import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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
  bool _useMetric = false;
  String _signStyle = 'circle';
  int _currentSpeedLimit = 0;

  late AnimationController _coffeeController;
  late Animation<double> _coffeeScale;
  late Animation<double> _coffeeOpacity;
  Timer? _coffeeStopTimer;
  Timer? _statePollTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkPermissions();

    _coffeeController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 3),
    );

    _coffeeScale = TweenSequence<double>([
      TweenSequenceItem(tween: Tween(begin: 1.0, end: 1.3), weight: 1),
      TweenSequenceItem(tween: Tween(begin: 1.3, end: 1.0), weight: 1),
    ]).animate(CurvedAnimation(
      parent: _coffeeController,
      curve: Curves.easeInOut,
    ));

    _coffeeOpacity = TweenSequence<double>([
      TweenSequenceItem(tween: Tween(begin: 1.0, end: 0.5), weight: 1),
      TweenSequenceItem(tween: Tween(begin: 0.5, end: 1.0), weight: 1),
    ]).animate(CurvedAnimation(
      parent: _coffeeController,
      curve: Curves.easeInOut,
    ));

    _coffeeController.repeat();
    _coffeeStopTimer = Timer(const Duration(seconds: 3), () {
      if (mounted) {
        _coffeeController.stop();
        _coffeeController.value = 0.0;
      }
    });
  }

  @override
  void dispose() {
    _coffeeStopTimer?.cancel();
    _statePollTimer?.cancel();
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

  void _syncPollTimer(bool active) {
    _statePollTimer?.cancel();
    if (active) {
      _statePollTimer = Timer.periodic(const Duration(seconds: 2), (_) {
        _refreshServiceSnapshot();
      });
    }
  }

  Future<void> _refreshServiceSnapshot() async {
    final snapshot = await OverlayService.getState();
    if (!mounted) return;
    setState(() {
      _bubbleActive = snapshot.isRunning;
      _hasOverlayPermission = snapshot.hasOverlayPermission;
      _currentSpeedLimit = snapshot.currentSpeedLimit;
    });
    if (!snapshot.isRunning) {
      _statePollTimer?.cancel();
    }
  }

  Future<void> _checkPermissions() async {
    final snapshot = await OverlayService.getState();
    bool locOk = false;
    try {
      final locPerm = await Geolocator.checkPermission();
      locOk = locPerm == LocationPermission.always ||
          locPerm == LocationPermission.whileInUse;
    } on MissingPluginException {
      locOk = false;
    } catch (_) {
      locOk = false;
    }

    if (!mounted) return;
    setState(() {
      _hasOverlayPermission = snapshot.hasOverlayPermission;
      _hasLocationPermission = locOk;
      _bubbleActive = snapshot.isRunning;
      _overLimitAllowance = snapshot.overLimitAllowance;
      _useMetric = snapshot.useMetric;
      _signStyle = snapshot.signStyle;
      _currentSpeedLimit = snapshot.currentSpeedLimit;
    });
    _syncPollTimer(snapshot.isRunning);
  }

  Future<void> _requestLocationPermission() async {
    try {
      var perm = await Geolocator.checkPermission();
      if (perm == LocationPermission.denied) {
        perm = await Geolocator.requestPermission();
      } else if (perm == LocationPermission.deniedForever) {
        await Geolocator.openAppSettings();
        return;
      }
      if (!mounted) return;
      setState(() {
        _hasLocationPermission = perm == LocationPermission.always ||
            perm == LocationPermission.whileInUse;
      });
    } on MissingPluginException {
      // No-op when running on desktop without a geolocation plugin mock.
    }
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
      if (!mounted) return;
      setState(() {
        _bubbleActive = false;
        _currentSpeedLimit = 0;
      });
      _syncPollTimer(false);
    } else {
      final shown = await OverlayService.show(
        overLimitAllowance: _overLimitAllowance,
        useMetric: _useMetric,
        signStyle: _signStyle,
      );
      if (!mounted) return;
      setState(() => _bubbleActive = shown);
      _syncPollTimer(shown);
    }
  }

  Future<void> _persistSettings({
    int? allowance,
    bool? useMetric,
    String? signStyle,
  }) async {
    final nextAllowance = (allowance ?? _overLimitAllowance).clamp(0, 50);
    final nextMetric = useMetric ?? _useMetric;
    final nextStyle = signStyle ?? _signStyle;

    setState(() {
      _overLimitAllowance = nextAllowance;
      _useMetric = nextMetric;
      _signStyle = nextStyle;
    });

    await OverlayService.updateSettings(
      overLimitAllowance: nextAllowance,
      useMetric: nextMetric,
      signStyle: nextStyle,
    );
  }

  String get _unitLabel => _useMetric ? 'km/h' : 'mph';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            return SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
              child: ConstrainedBox(
                constraints: BoxConstraints(
                  minHeight: (constraints.maxHeight - 32).clamp(0.0, double.infinity),
                ),
                child: IntrinsicHeight(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const SizedBox(height: 4),
                      Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _SignEmblem(
                            signStyle: _signStyle,
                            bubbleActive: _bubbleActive,
                            speedLimit: _currentSpeedLimit,
                          ),
                          const SizedBox(height: 12),
                          Text(
                            'Speed Limit',
                            style: Theme.of(context)
                                .textTheme
                                .headlineMedium
                                ?.copyWith(
                                  fontWeight: FontWeight.bold,
                                ),
                          ),
                          const SizedBox(height: 16),
                          SizedBox(
                            width: double.infinity,
                            height: 52,
                            child: FilledButton.icon(
                              onPressed: _toggleBubble,
                              icon: Icon(
                                _bubbleActive ? Icons.stop : Icons.play_arrow,
                              ),
                              label: Text(
                                _bubbleActive ? 'Stop Bubble' : 'Launch Bubble',
                                style: const TextStyle(fontSize: 18),
                              ),
                              style: FilledButton.styleFrom(
                                backgroundColor: _bubbleActive
                                    ? Colors.red[700]
                                    : Colors.green[700],
                              ),
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            _bubbleActive
                                ? 'Bubble is running. You can close this screen.'
                                : 'Tap Launch Bubble to start.\nYou can close this screen after.',
                            textAlign: TextAlign.center,
                            style: Theme.of(context)
                                .textTheme
                                .bodyMedium
                                ?.copyWith(
                                  color: Colors.white54,
                                ),
                          ),
                          const SizedBox(height: 16),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: Text(
                              'Settings',
                              style: Theme.of(context)
                                  .textTheme
                                  .titleSmall
                                  ?.copyWith(
                                    color: Colors.white38,
                                    letterSpacing: 1.2,
                                  ),
                            ),
                          ),
                          const SizedBox(height: 8),
                          Container(
                            decoration: BoxDecoration(
                              color: Colors.white.withValues(alpha: 0.06),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            padding: const EdgeInsets.symmetric(
                              horizontal: 16,
                              vertical: 8,
                            ),
                            child: Column(
                              children: [
                                Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        'Over-limit allowance',
                                        style: Theme.of(context)
                                            .textTheme
                                            .bodyMedium
                                            ?.copyWith(
                                              color: Colors.white70,
                                            ),
                                      ),
                                    ),
                                    IconButton(
                                      visualDensity: VisualDensity.compact,
                                      onPressed: _overLimitAllowance > 0
                                          ? () => _persistSettings(
                                                allowance:
                                                    _overLimitAllowance - 1,
                                              )
                                          : null,
                                      icon: const Icon(
                                        Icons.remove_circle_outline,
                                      ),
                                    ),
                                    Text(
                                      '$_overLimitAllowance $_unitLabel',
                                      style: Theme.of(context)
                                          .textTheme
                                          .bodyLarge
                                          ?.copyWith(
                                            color: Colors.white,
                                            fontWeight: FontWeight.bold,
                                          ),
                                    ),
                                    IconButton(
                                      visualDensity: VisualDensity.compact,
                                      onPressed: _overLimitAllowance < 50
                                          ? () => _persistSettings(
                                                allowance:
                                                    _overLimitAllowance + 1,
                                              )
                                          : null,
                                      icon: const Icon(
                                        Icons.add_circle_outline,
                                      ),
                                    ),
                                  ],
                                ),
                                const Divider(height: 10, color: Colors.white12),
                                Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        'Speed units',
                                        style: Theme.of(context)
                                            .textTheme
                                            .bodyMedium
                                            ?.copyWith(
                                              color: Colors.white70,
                                            ),
                                      ),
                                    ),
                                    _OptionToggle<bool>(
                                      value: _useMetric,
                                      options: const [
                                        MapEntry(false, 'mph'),
                                        MapEntry(true, 'km/h'),
                                      ],
                                      onChanged: (metric) =>
                                          _persistSettings(useMetric: metric),
                                    ),
                                  ],
                                ),
                                const Divider(height: 10, color: Colors.white12),
                                Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        'Sign style',
                                        style: Theme.of(context)
                                            .textTheme
                                            .bodyMedium
                                            ?.copyWith(
                                              color: Colors.white70,
                                            ),
                                      ),
                                    ),
                                    _OptionToggle<String>(
                                      value: _signStyle,
                                      options: const [
                                        MapEntry('circle', 'Circle'),
                                        MapEntry('us', 'US Sign'),
                                      ],
                                      onChanged: (style) =>
                                          _persistSettings(signStyle: style),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(height: 8),
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
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: AnimatedBuilder(
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
                            style: TextButton.styleFrom(
                              foregroundColor: Colors.amber,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

class _SignEmblem extends StatelessWidget {
  final String signStyle;
  final bool bubbleActive;
  final int speedLimit;

  const _SignEmblem({
    required this.signStyle,
    required this.bubbleActive,
    required this.speedLimit,
  });

  @override
  Widget build(BuildContext context) {
    final String? label =
        speedLimit > 0 ? '$speedLimit' : (bubbleActive ? '--' : null);

    if (signStyle == 'us') {
      return Container(
        width: 88,
        height: 108,
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.black87, width: 5),
          boxShadow: const [
            BoxShadow(
              color: Colors.black45,
              blurRadius: 8,
              offset: Offset(0, 3),
            ),
          ],
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text(
              'SPEED\nLIMIT',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: Colors.black,
                fontSize: 12,
                height: 1.05,
                fontWeight: FontWeight.w800,
                letterSpacing: 0.6,
              ),
            ),
            const SizedBox(height: 4),
            if (label != null)
              Text(
                label,
                style: TextStyle(
                  color: speedLimit > 0 ? Colors.black : Colors.black54,
                  fontSize: 34,
                  height: 1.0,
                  fontWeight: FontWeight.w900,
                ),
              )
            else
              const Icon(Icons.speed, size: 38, color: Colors.black87),
          ],
        ),
      );
    }

    return Container(
      width: 100,
      height: 100,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: Colors.white,
        border: Border.all(color: Colors.red[700]!, width: 8),
        boxShadow: const [
          BoxShadow(
            color: Colors.black45,
            blurRadius: 8,
            offset: Offset(0, 3),
          ),
        ],
      ),
      child: Center(
        child: label != null
            ? Text(
                label,
                style: TextStyle(
                  color: speedLimit > 0 ? Colors.black : Colors.black54,
                  fontSize: 36,
                  fontWeight: FontWeight.w900,
                ),
              )
            : const Icon(Icons.speed, size: 48, color: Colors.black87),
      ),
    );
  }
}

class _OptionToggle<T> extends StatelessWidget {
  final T value;
  final List<MapEntry<T, String>> options;
  final ValueChanged<T> onChanged;

  const _OptionToggle({
    required this.value,
    required this.options,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.black26,
        borderRadius: BorderRadius.circular(8),
      ),
      padding: const EdgeInsets.all(2),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final option in options)
            GestureDetector(
              onTap: () => onChanged(option.key),
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: value == option.key
                      ? Colors.red[700]
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  option.value,
                  style: TextStyle(
                    color: value == option.key ? Colors.white : Colors.white60,
                    fontWeight: value == option.key
                        ? FontWeight.bold
                        : FontWeight.normal,
                    fontSize: 13,
                  ),
                ),
              ),
            ),
        ],
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
      padding: const EdgeInsets.only(bottom: 2),
      child: TextButton.icon(
        onPressed: onTap,
        icon: const Icon(Icons.warning_amber, size: 18),
        label: Text(label),
        style: TextButton.styleFrom(
          foregroundColor: Colors.orangeAccent,
          visualDensity: VisualDensity.compact,
        ),
      ),
    );
  }
}
