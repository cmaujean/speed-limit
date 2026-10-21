import 'package:flutter/services.dart';

class OverlayStateSnapshot {
  final bool isRunning;
  final bool hasOverlayPermission;
  final int overLimitAllowance;
  final bool useMetric;
  final String signStyle;
  final int currentSpeedLimit;

  const OverlayStateSnapshot({
    this.isRunning = false,
    this.hasOverlayPermission = false,
    this.overLimitAllowance = 5,
    this.useMetric = false,
    this.signStyle = 'circle',
    this.currentSpeedLimit = 0,
  });

  factory OverlayStateSnapshot.fromMap(Map<Object?, Object?> map) {
    final style = (map['signStyle'] as String?) ?? 'circle';
    return OverlayStateSnapshot(
      isRunning: (map['isRunning'] as bool?) ?? false,
      hasOverlayPermission: (map['hasOverlayPermission'] as bool?) ?? false,
      overLimitAllowance: (map['overLimitAllowance'] as int?) ?? 5,
      useMetric: (map['useMetric'] as bool?) ?? false,
      signStyle: style == 'us' ? 'us' : 'circle',
      currentSpeedLimit: (map['currentSpeedLimit'] as int?) ?? 0,
    );
  }
}

class OverlayService {
  static const _channel = MethodChannel('com.speedlimit/overlay');

  static Future<OverlayStateSnapshot> getState() async {
    try {
      final raw =
          await _channel.invokeMethod<Map<Object?, Object?>>('getOverlayState');
      if (raw != null) {
        return OverlayStateSnapshot.fromMap(raw);
      }
      final hasPerm = await checkPermission();
      final running = await isRunning();
      return OverlayStateSnapshot(
        isRunning: running,
        hasOverlayPermission: hasPerm,
      );
    } on MissingPluginException {
      return const OverlayStateSnapshot();
    }
  }

  static Future<bool> checkPermission() async {
    try {
      final result =
          await _channel.invokeMethod<bool>('checkOverlayPermission');
      return result ?? false;
    } on MissingPluginException {
      return false;
    }
  }

  static Future<bool> isRunning() async {
    try {
      final result = await _channel.invokeMethod<bool>('isOverlayRunning');
      return result ?? false;
    } on MissingPluginException {
      return false;
    }
  }

  static Future<void> requestPermission() async {
    try {
      await _channel.invokeMethod('requestOverlayPermission');
    } on MissingPluginException {
      // No-op on non-Android test hosts without a mock handler.
    }
  }

  static Future<bool> show({
    int speedLimit = 0,
    int overLimitAllowance = 5,
    bool useMetric = false,
    String signStyle = 'circle',
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>('showOverlay', {
        'speedLimit': speedLimit,
        'overLimitAllowance': overLimitAllowance,
        'useMetric': useMetric,
        'signStyle': signStyle,
      });
      return result ?? false;
    } on MissingPluginException {
      return false;
    }
  }

  static Future<void> update({required int speedLimit}) async {
    try {
      await _channel.invokeMethod('updateOverlay', {
        'speedLimit': speedLimit,
      });
    } on MissingPluginException {
      // No-op on non-Android test hosts without a mock handler.
    }
  }

  static Future<void> updateAllowance({required int overLimitAllowance}) async {
    try {
      await _channel.invokeMethod('updateAllowance', {
        'overLimitAllowance': overLimitAllowance,
      });
    } on MissingPluginException {
      // No-op on non-Android test hosts without a mock handler.
    }
  }

  static Future<void> updateSettings({
    required int overLimitAllowance,
    required bool useMetric,
    required String signStyle,
  }) async {
    try {
      await _channel.invokeMethod('updateSettings', {
        'overLimitAllowance': overLimitAllowance,
        'useMetric': useMetric,
        'signStyle': signStyle,
      });
    } on MissingPluginException {
      // No-op on non-Android test hosts without a mock handler.
    }
  }

  static Future<void> hide() async {
    try {
      await _channel.invokeMethod('hideOverlay');
    } on MissingPluginException {
      // No-op on non-Android test hosts without a mock handler.
    }
  }
}
