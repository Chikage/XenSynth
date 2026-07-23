import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/ui/waterfall/waterfall_particle_system.dart';

void main() {
  test('uses the Android particle count and extends sustained-note life', () {
    final system = WaterfallParticleSystem(random: math.Random(7));

    system.spawn(
      pitch: 60.25,
      x: 120,
      y: 240,
      noteWidth: 5,
      pixelScale: 1,
      velocity: 127,
      track: 2,
      noteDurationSeconds: 2.4,
    );

    expect(system.particles, hasLength(20));
    expect(
      system.particles.every((particle) => particle.maxLife >= 2.52),
      isTrue,
    );
    expect(
      system.particles.every(
        (particle) => particle.hue == WaterfallParticleSystem.trackHues[2],
      ),
      isTrue,
    );
    final impact = system.impacts.single;
    expect(impact.pitch, 60.25);
    expect(impact.maxLife, closeTo(2.52, 0.000001));
    expect(impact.velocityRatio, 1);
    expect(impact.hue, WaterfallParticleSystem.trackHues[2]);
  });

  test('uses minimum impact life and replaces the same pitch impact', () {
    final system = WaterfallParticleSystem(random: math.Random(9));
    system.spawn(
      pitch: 60,
      x: 100,
      y: 200,
      noteWidth: 4,
      pixelScale: 1,
      velocity: 64,
      track: 0,
    );
    expect(system.impacts.single.maxLife, 0.26);

    system.spawn(
      pitch: 60,
      x: 100,
      y: 200,
      noteWidth: 4,
      pixelScale: 1,
      velocity: 127,
      track: 3,
      noteDurationSeconds: 1.5,
    );

    expect(system.impacts, hasLength(1));
    final impact = system.impacts.single;
    expect(impact.maxLife, closeTo(1.62, 0.000001));
    expect(impact.velocityRatio, 1);
    expect(impact.hue, WaterfallParticleSystem.trackHues[3]);
  });

  test('shares the 2D impact envelope across its full lifetime', () {
    final impact = WaterfallKeyImpact(
      pitch: 60,
      life: 0.26,
      maxLife: 0.26,
      velocityRatio: 0.75,
      hue: 0,
    );

    expect(impact.progress, 1);
    expect(impact.animationAmount, closeTo(0, 0.000001));
    expect(impact.fade, 1);

    impact.life = 0.13;
    expect(impact.progress, 0.5);
    expect(impact.animationAmount, closeTo(0.75, 0.000001));

    impact.life = 0;
    expect(impact.progress, 0);
    expect(impact.animationAmount, 0);
    expect(impact.fade, 0);
  });

  test('holds live impact for the note and releases an upward trace', () {
    final system = WaterfallParticleSystem(random: math.Random(29));

    system.beginInput(
      pointer: 7,
      pitch: 69.25,
      velocity: 96,
      x: 100,
      y: 200,
      noteWidth: 4,
    );
    for (var frame = 0; frame < 25; frame++) {
      system.advance(0.05);
    }

    final heldImpact = system.impacts.single;
    final trace = system.inputTraces.single;
    expect(heldImpact.held, isTrue);
    expect(heldImpact.fade, 1);
    expect(trace.held, isTrue);
    expect(trace.duration, closeTo(1.25, 0.000001));
    expect(trace.releaseAge, 0);
    expect(trace.velocityRatio, closeTo(96 / 127, 0.000001));

    system.endInput(7);
    for (var frame = 0; frame < 8; frame++) {
      system.advance(0.05);
    }

    expect(trace.held, isFalse);
    expect(trace.duration, closeTo(1.25, 0.000001));
    expect(trace.releaseAge, closeTo(0.4, 0.000001));
    expect(system.impacts, isEmpty);
  });

  test('advances particles with Android gravity and horizontal damping', () {
    final system = WaterfallParticleSystem(random: math.Random(11));
    system.spawn(
      pitch: 60,
      x: 100,
      y: 200,
      noteWidth: 4,
      pixelScale: 1,
      velocity: 96,
      track: 0,
    );
    final particle = system.particles.first;
    final initialX = particle.x;
    final initialY = particle.y;
    final initialVx = particle.vx;
    final initialVy = particle.vy;
    final initialLife = particle.life;

    expect(system.advance(0.05), isTrue);

    expect(particle.x, closeTo(initialX + initialVx * 0.05, 0.000001));
    expect(particle.y, closeTo(initialY + initialVy * 0.05, 0.000001));
    expect(particle.vy, closeTo(initialVy + particle.gravity * 0.05, 0.000001));
    expect(particle.vx.abs(), lessThan(initialVx.abs()));
    expect(particle.life, closeTo(initialLife - 0.05, 0.000001));
  });

  test('caps the live particle pool at the Android maximum', () {
    final system = WaterfallParticleSystem(random: math.Random(17));

    for (var index = 0; index < 100; index++) {
      system.spawn(
        pitch: 60 + index / 100,
        x: 100,
        y: 200,
        noteWidth: 4,
        pixelScale: 1,
        velocity: 127,
        track: index,
      );
    }

    expect(
      system.particles,
      hasLength(WaterfallParticleSystem.maximumParticleCount),
    );
  });

  test('supports a smaller live-particle budget for spatial rendering', () {
    final system = WaterfallParticleSystem(
      random: math.Random(23),
      maximumLiveParticles: 80,
    );

    for (var index = 0; index < 10; index++) {
      system.spawn(
        pitch: 60 + index / 100,
        x: 100,
        y: 200,
        noteWidth: 4,
        pixelScale: 1,
        velocity: 127,
        track: index,
      );
    }

    expect(system.particles, hasLength(80));
    expect(system.maximumLiveParticles, 80);
  });
}
