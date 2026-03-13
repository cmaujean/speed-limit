import 'package:flutter_test/flutter_test.dart';
import 'package:speed_limit/main.dart';

void main() {
  testWidgets('App renders launch screen', (WidgetTester tester) async {
    await tester.pumpWidget(const SpeedLimitApp());
    expect(find.text('Speed Limit'), findsOneWidget);
    expect(find.text('Launch Bubble'), findsOneWidget);
  });
}
