import 'package:flutter/services.dart';

class OverlayService {
  static const _channel = MethodChannel('com.speedlimit/overlay');

  static Future<bool> checkPermission() async {
    final result = await _channel.invokeMethod<bool>('checkOverlayPermission');
    return result ?? false;
  }

  static Future<bool> isRunning() async {
    final result = await _channel.invokeMethod<bool>('isOverlayRunning');
    return result ?? false;
  }

  static Future<void> requestPermission() async {
    await _channel.invokeMethod('requestOverlayPermission');
  }

  static Future<bool> show({int speedLimit = 0, int overLimitAllowance = 5}) async {
    final result = await _channel.invokeMethod<bool>('showOverlay', {
      'speedLimit': speedLimit,
      'overLimitAllowance': overLimitAllowance,
    });
    return result ?? false;
  }

  static Future<void> update({required int speedLimit}) async {
    await _channel.invokeMethod('updateOverlay', {
      'speedLimit': speedLimit,
    });
  }

  static Future<void> updateAllowance({required int overLimitAllowance}) async {
    await _channel.invokeMethod('updateAllowance', {
      'overLimitAllowance': overLimitAllowance,
    });
  }

  static Future<void> hide() async {
    await _channel.invokeMethod('hideOverlay');
  }
}
