import 'dart:math' as math;

/// Stateful hit-particle simulation ported from XenSynth-Android's waterfall.
class WaterfallParticleSystem {
  WaterfallParticleSystem({
    math.Random? random,
    int maximumLiveParticles = maximumParticleCount,
  }) : maximumLiveParticles = maximumLiveParticles.clamp(
         1,
         maximumParticleCount,
       ),
       _random = random ?? math.Random();

  static const gravity = 360.0;
  static const maximumParticleCount = 1200;
  static const minimumLife = 0.34;
  static const randomLifeRange = 0.36;
  static const keyImpactLife = 0.26;
  static const liveTrailMinimumDuration = 0.06;
  static const liveTrailRetentionSeconds = 20.0;
  static const minimumBurstParticleCount = 8;
  static const additionalBurstParticleCount = 12;
  static const _noteEffectTailSeconds = 0.12;
  static const _sustainScaleSeconds = 3.0;
  static const _minimumMotionScale = 0.28;
  static const _minimumGravityScale = 0.12;
  static const _damping = 0.18;
  static const trackHues = <double>[190, 28, 132, 48, 264, 158, 330, 88];

  final math.Random _random;
  final int maximumLiveParticles;
  final List<WaterfallHitParticle> _particles = [];
  final Map<int, WaterfallKeyImpact> _impacts = {};
  final Map<int, WaterfallKeyImpact> _liveImpacts = {};
  final List<WaterfallInputTrace> _inputTraces = [];
  final Map<int, WaterfallInputTrace> _activeInputTraces = {};

  List<WaterfallHitParticle> get particles => _particles;
  Iterable<WaterfallKeyImpact> get impacts sync* {
    yield* _impacts.values;
    yield* _liveImpacts.values;
  }

  List<WaterfallInputTrace> get inputTraces => _inputTraces;
  bool get isEmpty =>
      _particles.isEmpty &&
      _impacts.isEmpty &&
      _liveImpacts.isEmpty &&
      _inputTraces.isEmpty;

  void clear() {
    clearTimedEffects();
    _liveImpacts.clear();
    _inputTraces.clear();
    _activeInputTraces.clear();
  }

  void clearTimedEffects() {
    _particles.clear();
    _impacts.clear();
  }

  void beginInput({
    required int pointer,
    required double pitch,
    required int velocity,
    int track = 0,
    double? x,
    double? y,
    double? noteWidth,
    double pixelScale = 1,
  }) {
    final safeVelocity = velocity.clamp(1, 127);
    final existing = _activeInputTraces[pointer];
    if (existing != null && (existing.pitch - pitch).abs() < 0.001) {
      existing.velocityRatio = safeVelocity / 127;
      _liveImpacts[pointer]?.velocityRatio = safeVelocity / 127;
      return;
    }
    if (existing != null) endInput(pointer);

    final hue = trackHue(track);
    final trace = WaterfallInputTrace(
      pointer: pointer,
      pitch: pitch,
      velocityRatio: safeVelocity / 127,
      hue: hue,
    );
    _inputTraces.add(trace);
    _activeInputTraces[pointer] = trace;
    _liveImpacts[pointer] = WaterfallKeyImpact(
      pitch: pitch,
      life: keyImpactLife,
      maxLife: keyImpactLife,
      velocityRatio: safeVelocity / 127,
      hue: hue,
      held: true,
    );

    if (x != null && y != null && noteWidth != null) {
      _spawnParticles(
        x: x,
        y: y,
        noteWidth: noteWidth,
        pixelScale: pixelScale,
        velocity: safeVelocity,
        hue: hue,
      );
    }
  }

  void endInput(int pointer) {
    final trace = _activeInputTraces.remove(pointer);
    if (trace != null) {
      trace
        ..held = false
        ..duration = math.max(trace.duration, liveTrailMinimumDuration);
    }
    _liveImpacts[pointer]?.release();
  }

  void spawn({
    required double pitch,
    required double x,
    required double y,
    required double noteWidth,
    required double pixelScale,
    required int velocity,
    required int track,
    double? noteDurationSeconds,
  }) {
    final safeVelocity = velocity.clamp(1, 127);
    final hue = trackHue(track);
    _spawnParticles(
      x: x,
      y: y,
      noteWidth: noteWidth,
      pixelScale: pixelScale,
      velocity: safeVelocity,
      hue: hue,
      noteDurationSeconds: noteDurationSeconds,
    );

    final impactLife = noteEffectLife(keyImpactLife, noteDurationSeconds);
    _impacts[(pitch * 10000).round()] = WaterfallKeyImpact(
      pitch: pitch,
      life: impactLife,
      maxLife: impactLife,
      velocityRatio: velocity.clamp(0, 127) / 127,
      hue: hue,
    );
  }

  bool advance(double deltaSeconds) {
    if (isEmpty || !deltaSeconds.isFinite || deltaSeconds <= 0) {
      return false;
    }
    final delta = deltaSeconds.clamp(0.0, 0.08);
    final damping = math.pow(_damping, delta).toDouble();
    var changed = false;
    for (var index = _particles.length - 1; index >= 0; index--) {
      final particle = _particles[index];
      particle.life -= delta;
      changed = true;
      if (particle.life <= 0) {
        _particles.removeAt(index);
        continue;
      }
      particle.x += particle.vx * delta;
      particle.y += particle.vy * delta;
      particle.vy += particle.gravity * delta;
      particle.vx *= damping;
    }
    _impacts.removeWhere((_, impact) {
      impact.life -= delta;
      changed = true;
      return impact.life <= 0;
    });
    _liveImpacts.removeWhere((_, impact) {
      impact.age += delta;
      if (impact.held) {
        changed = true;
        return false;
      }
      impact.life -= delta;
      changed = true;
      return impact.life <= 0;
    });
    for (final trace in _inputTraces) {
      trace.age += delta;
      if (trace.held) trace.duration += delta;
      changed = true;
    }
    _inputTraces.removeWhere(
      (trace) => !trace.held && trace.releaseAge > liveTrailRetentionSeconds,
    );
    return changed;
  }

  void _spawnParticles({
    required double x,
    required double y,
    required double noteWidth,
    required double pixelScale,
    required int velocity,
    required double hue,
    double? noteDurationSeconds,
  }) {
    final safeScale = pixelScale.isFinite && pixelScale > 0 ? pixelScale : 1.0;
    final velocityRatio = velocity / 127;
    final count =
        (minimumBurstParticleCount +
                velocityRatio * additionalBurstParticleCount)
            .round();
    final spread = math.max(4 * safeScale, noteWidth * 1.85);
    for (var index = 0; index < count; index++) {
      final upward = index % 5 != 0;
      final baseLife = minimumLife + _random.nextDouble() * randomLifeRange;
      final life = particleLife(baseLife, noteDurationSeconds);
      final velocityScale =
          (0.68 + velocityRatio * 1.02) * particleMotionScale(life);
      final vx = (_random.nextDouble() - 0.5) * 170 * velocityScale * safeScale;
      final vy = upward
          ? (-120 - _random.nextDouble() * 210) * velocityScale * safeScale
          : (42 + _random.nextDouble() * 118) * velocityScale * safeScale;
      _particles.add(
        WaterfallHitParticle(
          x: x + (_random.nextDouble() - 0.5) * spread,
          y: y + (_random.nextDouble() - 0.5) * 7 * safeScale,
          vx: vx,
          vy: vy,
          life: life,
          maxLife: life,
          size: (2.2 + _random.nextDouble() * 3.7) * safeScale,
          hue: hue,
          lightness: 0.66 + _random.nextDouble() * 0.24,
          gravity: gravity * particleGravityScale(life) * safeScale,
        ),
      );
    }
    final overflow = _particles.length - maximumLiveParticles;
    if (overflow > 0) _particles.removeRange(0, overflow);
  }

  static double particleLife(double baseLife, double? noteDurationSeconds) {
    return noteEffectLife(baseLife, noteDurationSeconds);
  }

  static double noteEffectLife(double baseLife, double? noteDurationSeconds) {
    final noteLife =
        noteDurationSeconds != null &&
            noteDurationSeconds.isFinite &&
            noteDurationSeconds > 0
        ? noteDurationSeconds
        : 0.0;
    return math.max(baseLife, noteLife + _noteEffectTailSeconds);
  }

  static double particleMotionScale(double life) {
    final sustain = ((life - minimumLife) / _sustainScaleSeconds).clamp(
      0.0,
      1.0,
    );
    return 1 - (1 - _minimumMotionScale) * sustain;
  }

  static double particleGravityScale(double life) {
    final sustain = ((life - minimumLife) / _sustainScaleSeconds).clamp(
      0.0,
      1.0,
    );
    return 1 - (1 - _minimumGravityScale) * sustain;
  }

  static double trackHue(int track) {
    return trackHues[track.abs() % trackHues.length];
  }
}

class WaterfallHitParticle {
  WaterfallHitParticle({
    required this.x,
    required this.y,
    required this.vx,
    required this.vy,
    required this.life,
    required this.maxLife,
    required this.size,
    required this.hue,
    required this.lightness,
    required this.gravity,
  });

  double x;
  double y;
  double vx;
  double vy;
  double life;
  final double maxLife;
  final double size;
  final double hue;
  final double lightness;
  final double gravity;
}

class WaterfallKeyImpact {
  WaterfallKeyImpact({
    required this.pitch,
    required this.life,
    required this.maxLife,
    required this.velocityRatio,
    required this.hue,
    this.held = false,
  });

  final double pitch;
  double life;
  final double maxLife;
  double velocityRatio;
  final double hue;
  bool held;
  double age = 0;

  void release() {
    if (!held) return;
    held = false;
    life = maxLife;
  }

  double get progress =>
      maxLife > 0 ? (life / maxLife).clamp(0.0, 1.0).toDouble() : 0.0;

  /// Shared 2D/3D impact envelope: rest -> peak -> rest.
  double get animationAmount => held
      ? (age / 0.08).clamp(0.0, 1.0) * velocityRatio.clamp(0.0, 1.0)
      : math.sin(progress * math.pi) * velocityRatio.clamp(0.0, 1.0);

  double get fade => held ? 1 : math.pow(progress, 0.72).toDouble();
}

class WaterfallInputTrace {
  WaterfallInputTrace({
    required this.pointer,
    required this.pitch,
    required this.velocityRatio,
    required this.hue,
  });

  final int pointer;
  final double pitch;
  double velocityRatio;
  final double hue;
  double age = 0;
  double duration = 0;
  bool held = true;

  double get releaseAge => math.max(0.0, age - duration);
}
