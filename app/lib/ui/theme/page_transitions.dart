// The push itself follows the shape of
// Flutter's CupertinoPageTransition; the back gesture reuses the framework's
// public PredictiveBackRoute hooks on TransitionRoute.

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show PredictiveBackEvent;

import 'motion.dart';

/// Secondary pages push in from the right, iOS style: the new page slides over
/// with a soft shadow on its leading edge while the page underneath drifts a
/// quarter to the left and dims a little.
///
/// The back gesture follows the finger, two ways:
///
/// - Android's system back gesture (Android 14+, when the app opts into
///   predictive back with `android:enableOnBackInvokedCallback="true"`) drives
///   the same transition through the framework's predictive-back hooks;
/// - a 20dp strip at the leading edge takes an in-app swipe (three-button
///   navigation, tablets, desktop).
///
/// Release past the middle, or fling right, and the page leaves; otherwise it
/// settles back. The in-app swipe hands its speed to a spring for either.
class AppPageTransitionsBuilder extends PageTransitionsBuilder {
  const AppPageTransitionsBuilder() : _duration = AppMotion.push;

  /// The same transition taking no time, for [AppMotion.reduced]. A builder has
  /// no context to ask, and the route reads this duration from the theme at
  /// every push and pop, so the theme picks this one (lib/app.dart).
  const AppPageTransitionsBuilder.instant() : _duration = Duration.zero;

  final Duration _duration;

  @override
  Duration get transitionDuration => _duration;

  @override
  DelegatedTransitionBuilder? get delegatedTransition =>
      (context, animation, secondaryAnimation, allowSnapshotting, child) =>
          _Recede(animation: secondaryAnimation, linear: false, child: child ?? const SizedBox.shrink());

  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    final linear = route.popGestureInProgress;
    return _BackGesture(
      route: route,
      child: _Recede(
        animation: secondaryAnimation,
        linear: linear,
        child: _PushIn(animation: animation, linear: linear, child: child),
      ),
    );
  }
}

/// The incoming page: slides in from the right with a shadow on its leading
/// edge. Linear (finger-driven) while a back gesture is in progress.
class _PushIn extends StatefulWidget {
  const _PushIn({required this.animation, required this.linear, required this.child});

  final Animation<double> animation;
  final bool linear;
  final Widget child;

  @override
  State<_PushIn> createState() => _PushInState();
}

class _PushInState extends State<_PushIn> {
  late CurvedAnimation _curved;

  @override
  void initState() {
    super.initState();
    _curved = _curve();
  }

  CurvedAnimation _curve() => CurvedAnimation(
    parent: widget.animation,
    curve: widget.linear ? Curves.linear : AppMotion.glide,
    reverseCurve: widget.linear ? Curves.linear : AppMotion.glide.flipped,
  );

  @override
  void didUpdateWidget(_PushIn old) {
    super.didUpdateWidget(old);
    if (old.animation != widget.animation || old.linear != widget.linear) {
      _curved.dispose();
      _curved = _curve();
    }
  }

  @override
  void dispose() {
    _curved.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final position = Tween<Offset>(begin: const Offset(1, 0), end: Offset.zero).animate(_curved);
    return SlideTransition(
      position: position,
      textDirection: Directionality.of(context),
      child: Stack(
        clipBehavior: Clip.none,
        fit: StackFit.passthrough,
        children: [
          widget.child,
          PositionedDirectional(
            start: -14,
            width: 14,
            top: 0,
            bottom: 0,
            child: IgnorePointer(
              child: FadeTransition(
                opacity: _curved,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: AlignmentDirectional.centerStart,
                      end: AlignmentDirectional.centerEnd,
                      colors: [
                        Colors.black.withValues(alpha: 0),
                        Colors.black.withValues(alpha: dark ? 0.22 : 0.06),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// The page underneath while another is pushed over it: drifts a quarter to the
/// left and dims slightly.
class _Recede extends StatefulWidget {
  const _Recede({required this.animation, required this.linear, required this.child});

  final Animation<double> animation;
  final bool linear;
  final Widget child;

  @override
  State<_Recede> createState() => _RecedeState();
}

class _RecedeState extends State<_Recede> {
  late CurvedAnimation _curved;

  @override
  void initState() {
    super.initState();
    _curved = _curve();
  }

  CurvedAnimation _curve() => CurvedAnimation(
    parent: widget.animation,
    curve: widget.linear ? Curves.linear : AppMotion.glide,
    reverseCurve: widget.linear ? Curves.linear : AppMotion.glide.flipped,
  );

  @override
  void didUpdateWidget(_Recede old) {
    super.didUpdateWidget(old);
    if (old.animation != widget.animation || old.linear != widget.linear) {
      _curved.dispose();
      _curved = _curve();
    }
  }

  @override
  void dispose() {
    _curved.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    return SlideTransition(
      position: Tween<Offset>(begin: Offset.zero, end: const Offset(-0.25, 0)).animate(_curved),
      textDirection: Directionality.of(context),
      transformHitTests: false,
      child: Stack(
        fit: StackFit.passthrough,
        children: [
          widget.child,
          Positioned.fill(
            child: IgnorePointer(
              child: FadeTransition(
                opacity: _curved,
                child: ColoredBox(color: Colors.black.withValues(alpha: dark ? 0.32 : 0.10)),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Drives the route's transition from a back gesture: Android's predictive back
/// events, or a swipe from the leading edge.
class _BackGesture extends StatefulWidget {
  const _BackGesture({required this.route, required this.child});

  final PageRoute<dynamic> route;
  final Widget child;

  @override
  State<_BackGesture> createState() => _BackGestureState();
}

class _BackGestureState extends State<_BackGesture> with WidgetsBindingObserver, SingleTickerProviderStateMixin {
  static const _edge = 20.0;
  static const _minFlingVelocity = 1.0; // screen widths per second

  late final AnimationController _settle = AnimationController(vsync: this);
  HorizontalDragGestureRecognizer? _drag;

  /// Route progress while the in-app swipe owns the route (1 = fully shown).
  double? _progress;

  PageRoute<dynamic> get _route => widget.route;

  bool get _canGesture => _route.isCurrent && _route.popGestureEnabled;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _settle.addListener(() {
      if (_route.isCurrent) {
        _route.handleUpdateBackGestureProgress(progress: _settle.value);
      }
      // Off screen: the rest of the spring has nothing to show, so don't wait.
      if (_settle.value == 0 && _settle.isAnimating) _settle.stop();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _drag?.dispose();
    _settle.dispose();
    super.dispose();
  }

  // ── Android predictive back ────────────────────────────────────────────────

  @override
  bool handleStartBackGesture(PredictiveBackEvent backEvent) {
    if (backEvent.isButtonEvent || !_canGesture || _progress != null) {
      return false;
    }
    _route.handleStartBackGesture(progress: 1 - backEvent.progress);
    return true;
  }

  @override
  void handleUpdateBackGestureProgress(PredictiveBackEvent backEvent) {
    _route.handleUpdateBackGestureProgress(progress: 1 - backEvent.progress);
  }

  @override
  void handleCancelBackGesture() => _route.handleCancelBackGesture();

  @override
  void handleCommitBackGesture() => _route.handleCommitBackGesture();

  // ── In-app edge swipe ──────────────────────────────────────────────────────

  void _onPointerDown(PointerDownEvent event) {
    if (!_canGesture || _settle.isAnimating) return;
    (_drag ??= HorizontalDragGestureRecognizer(debugOwner: this)
          ..onStart = _onStart
          ..onUpdate = _onUpdate
          ..onEnd = _onEnd
          ..onCancel = _onCancel)
        .addPointer(event);
  }

  double get _width => context.size?.width ?? MediaQuery.sizeOf(context).width;

  double _sign() => Directionality.of(context) == TextDirection.rtl ? -1 : 1;

  void _onStart(DragStartDetails details) {
    if (!_canGesture) return;
    _progress = 1;
    _route.handleStartBackGesture(progress: 1);
  }

  void _onUpdate(DragUpdateDetails details) {
    final progress = _progress;
    if (progress == null) return;
    final next = (progress - _sign() * details.primaryDelta! / _width).clamp(0.0, 1.0);
    _progress = next;
    _route.handleUpdateBackGestureProgress(progress: next);
  }

  void _onEnd(DragEndDetails details) {
    final progress = _progress;
    if (progress == null) return;
    final velocity = _sign() * details.velocity.pixelsPerSecond.dx / _width;
    final leave = velocity.abs() >= _minFlingVelocity ? velocity > 0 : progress < 0.5;
    _finish(from: progress, leave: leave, velocity: -velocity);
  }

  void _onCancel() {
    final progress = _progress;
    if (progress != null) _finish(from: progress, leave: false);
  }

  /// [velocity]: route values a second.
  void _finish({required double from, required bool leave, double velocity = 0}) {
    final target = leave ? 0.0 : 1.0;
    _settle.value = from;
    void done() {
      _progress = null;
      if (!mounted) return;
      if (leave) {
        _route.handleCommitBackGesture();
      } else {
        _route.handleCancelBackGesture();
      }
    }

    if (AppMotion.reduced(context) || from == target) {
      if (_route.isCurrent) {
        _route.handleUpdateBackGestureProgress(progress: target);
      }
      done();
      return;
    }
    _settle
        .animateWith(AppMotion.settle(AppMotion.sheetSpring, from, target, velocity, landing: !leave))
        .whenCompleteOrCancel(done);
  }

  @override
  Widget build(BuildContext context) {
    final inset = MediaQuery.paddingOf(context);
    final edge = _edge + (Directionality.of(context) == TextDirection.rtl ? inset.right : inset.left);
    return Stack(
      fit: StackFit.passthrough,
      children: [
        widget.child,
        PositionedDirectional(
          start: 0,
          width: edge,
          top: 0,
          bottom: 0,
          child: Listener(onPointerDown: _onPointerDown, behavior: HitTestBehavior.translucent),
        ),
      ],
    );
  }
}
