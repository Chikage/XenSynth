import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/ui/waterfall/waterfall_particle_system.dart';

void main() {
  test(
    'uses the Android particle count and extends sustained-note life',
    () {
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
    },
  );

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
}
