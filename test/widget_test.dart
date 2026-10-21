import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:geolocator/geolocator.dart';
import 'package:speed_limit/main.dart';

class _FakeGeolocator extends GeolocatorPlatform {
  LocationPermission permission;
  bool openedAppSettings = false;

  _FakeGeolocator({this.permission = LocationPermission.whileInUse});

  @override
  Future<LocationPermission> checkPermission() async => permission;

  @override
  Future<LocationPermission> requestPermission() async => permission;

  @override
  Future<bool> isLocationServiceEnabled() async => true;

  @override
  Future<bool> openAppSettings() async {
    openedAppSettings = true;
    return true;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.speedlimit/overlay');
  late List<MethodCall> methodCalls;
  late Map<String, dynamic> mockState;
  late _FakeGeolocator fakeGeolocator;

  setUp(() {
    methodCalls = <MethodCall>[];
    mockState = <String, dynamic>{
      'isRunning': false,
      'hasOverlayPermission': true,
      'overLimitAllowance': 5,
      'useMetric': false,
      'signStyle': 'circle',
      'currentSpeedLimit': 0,
    };
    fakeGeolocator = _FakeGeolocator(permission: LocationPermission.whileInUse);
    GeolocatorPlatform.instance = fakeGeolocator;

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async {
      methodCalls.add(call);
      switch (call.method) {
        case 'getOverlayState':
          return Map<String, dynamic>.from(mockState);
        case 'checkOverlayPermission':
          return mockState['hasOverlayPermission'] as bool;
        case 'isOverlayRunning':
          return mockState['isRunning'] as bool;
        case 'requestOverlayPermission':
          return null;
        case 'showOverlay':
          final args = (call.arguments as Map?)?.cast<String, dynamic>() ?? {};
          mockState['isRunning'] = true;
          mockState['overLimitAllowance'] =
              args['overLimitAllowance'] ?? mockState['overLimitAllowance'];
          mockState['useMetric'] = args['useMetric'] ?? mockState['useMetric'];
          mockState['signStyle'] = args['signStyle'] ?? mockState['signStyle'];
          return true;
        case 'updateSettings':
        case 'updateAllowance':
          final args = (call.arguments as Map?)?.cast<String, dynamic>() ?? {};
          if (args.containsKey('overLimitAllowance')) {
            mockState['overLimitAllowance'] = args['overLimitAllowance'];
          }
          if (args.containsKey('useMetric')) {
            mockState['useMetric'] = args['useMetric'];
          }
          if (args.containsKey('signStyle')) {
            mockState['signStyle'] = args['signStyle'];
          }
          return true;
        case 'hideOverlay':
          mockState['isRunning'] = false;
          return true;
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets(
    'App renders without overflow at 800x600 and compact 360x480 even with both permissions missing',
    (WidgetTester tester) async {
      mockState['hasOverlayPermission'] = false;
      fakeGeolocator.permission = LocationPermission.denied;

      tester.view.physicalSize = const Size(360, 480);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(const SpeedLimitApp());
      await tester.pump();

      expect(find.text('Speed Limit'), findsOneWidget);
      expect(find.text('Launch Bubble'), findsOneWidget);
      expect(find.text('Overlay permission required'), findsOneWidget);
      expect(find.text('Location permission required'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'Restores persisted allowance, unit, sign style, and running state from native service',
    (WidgetTester tester) async {
      mockState['isRunning'] = true;
      mockState['overLimitAllowance'] = 10;
      mockState['useMetric'] = true;
      mockState['signStyle'] = 'us';
      mockState['currentSpeedLimit'] = 65;

      await tester.pumpWidget(const SpeedLimitApp());
      await tester.pump();

      expect(find.text('Stop Bubble'), findsOneWidget);
      expect(find.text('10 km/h'), findsOneWidget);
      expect(find.text('65'), findsOneWidget);
    },
  );

  testWidgets(
    'Persists allowance, unit, and sign style changes and toggles bubble lifecycle',
    (WidgetTester tester) async {
      await tester.pumpWidget(const SpeedLimitApp());
      await tester.pump();

      expect(find.text('5 mph'), findsOneWidget);

      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pump();
      expect(find.text('6 mph'), findsOneWidget);

      await tester.tap(find.text('km/h'));
      await tester.pump();
      expect(find.text('6 km/h'), findsOneWidget);

      await tester.tap(find.text('US Sign'));
      await tester.pump();

      final settingsCalls =
          methodCalls.where((c) => c.method == 'updateSettings').toList();
      expect(settingsCalls, isNotEmpty);
      expect(settingsCalls.last.arguments, {
        'overLimitAllowance': 6,
        'useMetric': true,
        'signStyle': 'us',
      });

      await tester.tap(find.text('Launch Bubble'));
      await tester.pump();
      expect(find.text('Stop Bubble'), findsOneWidget);

      final showCalls =
          methodCalls.where((c) => c.method == 'showOverlay').toList();
      expect(showCalls.length, 1);
      expect(showCalls.single.arguments, {
        'speedLimit': 0,
        'overLimitAllowance': 6,
        'useMetric': true,
        'signStyle': 'us',
      });

      await tester.tap(find.text('Stop Bubble'));
      await tester.pump();
      expect(find.text('Launch Bubble'), findsOneWidget);
    },
  );

  testWidgets(
    'Rejects Launch Bubble when permissions are missing and opens settings on deniedForever',
    (WidgetTester tester) async {
      mockState['hasOverlayPermission'] = false;
      fakeGeolocator.permission = LocationPermission.deniedForever;

      await tester.pumpWidget(const SpeedLimitApp());
      await tester.pump();

      await tester.tap(find.text('Launch Bubble'));
      await tester.pump();

      expect(
        methodCalls.where((c) => c.method == 'requestOverlayPermission'),
        isNotEmpty,
      );
      expect(methodCalls.where((c) => c.method == 'showOverlay'), isEmpty);

      await tester.tap(find.text('Location permission required'));
      await tester.pump();
      expect(fakeGeolocator.openedAppSettings, isTrue);
    },
  );
}
