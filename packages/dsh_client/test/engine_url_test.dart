import 'dart:io';
import 'dart:typed_data';

import 'package:dsh_client/dsh_client.dart';
import 'package:test/test.dart';

void main() {
  group('ownUri', () {
    final engine = DshConnection(Uri.parse('http://127.0.0.1:3080'));

    test('takes the engine address, with its path and query', () {
      final own = engine.ownUri('http://127.0.0.1:3080/api/dsh-emoji/assets/a.png?v=2');
      expect(own, Uri.parse('http://127.0.0.1:3080/api/dsh-emoji/assets/a.png?v=2'));
    });

    test('keeps a query as written, repeated keys and all', () {
      expect(engine.ownUri('http://127.0.0.1:3080/a.png?part=a&part=b&sig=x%2By')?.query, 'part=a&part=b&sig=x%2By');
    });

    test('refuses every other address, which must never see the cookie', () {
      for (final url in [
        'http://127.0.0.1:3081/a.png',
        'https://127.0.0.1:3080/a.png',
        'http://localhost:3080/a.png',
        'http://example.com/a.png',
        'http://127.0.0.1:3080@example.com/a.png',
        'http://user@127.0.0.1:3080/a.png',
        'not a url',
      ]) {
        expect(engine.ownUri(url), isNull, reason: url);
      }
    });

    test('a tunnelled engine answers to the name it knows itself by, on the dialled address', () {
      final tunnel = DshConnection(Uri.parse('http://127.0.0.1:49152'), hostHeader: '127.0.0.1:3080');
      expect(tunnel.ownUri('http://127.0.0.1:3080/a.png'), Uri.parse('http://127.0.0.1:49152/a.png'));
      expect(tunnel.ownUri('http://127.0.0.1:49152/a.png'), Uri.parse('http://127.0.0.1:49152/a.png'));
      expect(tunnel.ownUri('http://127.0.0.1:9999/a.png'), isNull);
    });

    test('stays inside the mount path', () {
      final mounted = DshConnection(Uri.parse('http://127.0.0.1:3080/dsh'));
      expect(mounted.ownUri('http://127.0.0.1:3080/dsh/a.png'), Uri.parse('http://127.0.0.1:3080/dsh/a.png'));
      expect(mounted.ownUri('http://127.0.0.1:3080/other/a.png'), isNull);
      expect(mounted.ownUri('http://127.0.0.1:3080/dshx/a.png'), isNull);
    });
  });

  group('readEngineUrl', () {
    late HttpServer server;
    late DshClient client;
    late Uri base;
    final cookies = <String?>[];
    final paths = <String>[];
    final png = Uint8List.fromList([137, 80, 78, 71]);

    setUp(() async {
      cookies.clear();
      paths.clear();
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      base = Uri.parse('http://127.0.0.1:${server.port}');
      server.listen((request) async {
        final cookie = request.headers.value(HttpHeaders.cookieHeader);
        cookies.add(cookie);
        paths.add(request.uri.path);
        final response = request.response;
        if (request.uri.path == '/moved') {
          response.statusCode = HttpStatus.found;
          response.headers.set(HttpHeaders.locationHeader, '/a.png');
        } else if (cookie != 'dsh=v1') {
          response.statusCode = HttpStatus.unauthorized;
        } else {
          response.add(png);
        }
        await response.close();
      });
      final authority = '127.0.0.1:${server.port}';
      client = DshClient.withCarrier(
        DshHttpCarrier(DshConnection(base, cookie: DshCookie(name: 'dsh', value: 'v1', authority: authority))),
      );
    });

    tearDown(() async {
      await client.close();
      await server.close(force: true);
    });

    test('a protected engine image loads with the session cookie', () async {
      final url = base.resolve('/api/dsh-emoji/a.png').toString();
      expect(client.isEngineUrl(url), isTrue);
      expect(await client.readEngineUrl(url, maxBytes: 1024), png);
      expect(cookies, ['dsh=v1']);
    });

    test('an answer other than 200 is an error, not an image', () async {
      final bare = DshClient.withCarrier(DshHttpCarrier(DshConnection(base)));
      addTearDown(bare.close);
      expect(
        bare.readEngineUrl(base.resolve('/a.png').toString(), maxBytes: 1024),
        throwsA(isA<DshHttpException>().having((e) => e.statusCode, 'statusCode', 401)),
      );
    });

    test('a redirect is not followed', () async {
      await expectLater(
        client.readEngineUrl(base.resolve('/moved').toString(), maxBytes: 1024),
        throwsA(isA<DshHttpException>()),
      );
      expect(paths, ['/moved']);
    });

    test('the connection itself refuses another address, not only the client', () async {
      final connection = DshConnection(base, cookie: const DshCookie(name: 'dsh', value: 'v1', authority: 'x'));
      addTearDown(connection.close);
      await expectLater(connection.getBoundedOwn('http://example.com/a.png', maxBytes: 1024), throwsArgumentError);
      expect(paths, isEmpty);
    });

    test('another address is never requested', () async {
      const url = 'http://example.com/a.png';
      expect(client.isEngineUrl(url), isFalse);
      expect(() => client.readEngineUrl(url, maxBytes: 1024), throwsArgumentError);
      expect(paths, isEmpty);
    });
  });
}
