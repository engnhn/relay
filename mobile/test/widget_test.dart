import 'package:flutter_test/flutter_test.dart';
import 'package:relay_mobile/main.dart';

void main() {
  testWidgets('shows relay connection fields', (tester) async {
    await tester.pumpWidget(const RelayApp());

    expect(find.text('relay'), findsWidgets);
    expect(find.text('linux machine ip address'), findsOneWidget);
    expect(find.text('port'), findsOneWidget);
    expect(find.text('connect'), findsOneWidget);
  });
}
