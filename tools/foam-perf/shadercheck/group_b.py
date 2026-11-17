W = 'src/main/resources/rs117/hd/utils/water.glsl'

edits = {W: [
    # Tidy the aliases left by the shared shore wave constants
    ('''float waveTravelDistance(float shoreDist) {
    const float reach = shoreWaveReach;
    const float endSpeed = shoreWaveEndSpeed;
    if (endSpeed >= 0.999)
        return shoreDist;
    // Full speed beyond the reach, and stopping short of zero speed inside the shore
    float d = clamp(shoreDist, -0.9 * endSpeed / (1.0 - endSpeed) * reach, reach);
    float travel = reach / (1.0 - endSpeed) * log((endSpeed + (1.0 - endSpeed) * d / reach) / endSpeed);
    return travel + max(shoreDist - reach, 0.0);
}
''',
     '''float waveTravelDistance(float shoreDist) {
    const float reach = shoreWaveReach;
    const float endSpeed = shoreWaveEndSpeed;
    if (endSpeed >= 0.999)
        return shoreDist;
    // Full speed beyond the reach, and stopping short of zero speed inside the shore
    float d = clamp(shoreDist, -0.9 * endSpeed / (1.0 - endSpeed) * reach, reach);
    float travel = reach / (1.0 - endSpeed) * log((endSpeed + (1.0 - endSpeed) * d / reach) / endSpeed);
    return travel + max(shoreDist - reach, 0.0);
}
'''.replace('''    const float reach = shoreWaveReach;
    const float endSpeed = shoreWaveEndSpeed;
''', '').replace('endSpeed', 'shoreWaveEndSpeed').replace('reach', 'shoreWaveReach').replace('the shoreWaveReach', 'the reach')),
    ('''    const float startTravel = shoreWaveStartTravel;
''', ''),
    ('''    float minAge = max((startTravel - travel) / maxSpeed, 0.0);
    float maxAge = (startTravel - travel + maxThickness) / minSpeed;
''',
     '''    float minAge = max((shoreWaveStartTravel - travel) / maxSpeed, 0.0);
    float maxAge = (shoreWaveStartTravel - travel + maxThickness) / minSpeed;
'''),
    ('''                float front = startTravel - age * speed;
''',
     '''                float front = shoreWaveStartTravel - age * speed;
'''),

    # Boat wakes: named noise and the big ships' shape worked out where it's used
    ('''//  shape: for the big ships, their arms' angle, how long the arms last and how far the wash reaches, relative to other
//  boats', then how much further back the wash starts, in tiles, or 1, 1, 1, 0
//  washScale: how wide the wash is, relative to other boats'
//  noises: the noise at this spot, shared by every wake here, each worked out the first time it's needed, or -1 until
//  then: the breakup, the raggedness, then two used after this
vec3 boatWakeFoam(vec2 world, float side, float fromBow, float age, bool arms, bool squareBow, vec4 shape,
    float washScale, inout vec4 noises) {
    float washStart = BOAT_WAKE_BOW_LENGTH + BOAT_WASH_OFFSET + shape.w;
    float angleSlope = tan(radians(BOAT_WAKE_ANGLE)) * shape.x;
    float life = arms ? age / (BOAT_WAKE_LIFETIME * shape.y) : 1.0;
''',
     '''//  bigShip: true for the big NPC ships, whose wakes are shaped by the BOAT_SHIP settings
//  wakeNoise: the noise at this spot, with its foam noise worked out here the first time it's needed
vec3 boatWakeFoam(vec2 world, float side, float fromBow, float age, bool arms, bool squareBow, bool bigShip,
    inout WakeNoise wakeNoise) {
    // The big ships' arms' angle, how long the arms last, how far the wash reaches and how wide it is, relative to
    // other boats', then how much further back the wash starts, in tiles
    float armAngle = bigShip ? BOAT_SHIP_ANGLE : 1.0;
    float armLifetime = bigShip ? BOAT_SHIP_LIFETIME : 1.0;
    float washLength = bigShip ? BOAT_SHIP_WASH_LENGTH : 1.0;
    float washScale = bigShip ? BOAT_SHIP_WASH_WIDTH : 1.0;
    float washOffset = bigShip ? BOAT_SHIP_WASH_OFFSET : 0.0;

    float washStart = BOAT_WAKE_BOW_LENGTH + BOAT_WASH_OFFSET + washOffset;
    float angleSlope = tan(radians(BOAT_WAKE_ANGLE)) * armAngle;
    float life = arms ? age / (BOAT_WAKE_LIFETIME * armLifetime) : 1.0;
'''),
    ('''    float washLife = min(age / BOAT_WASH_LIFETIME, (fromBow - washStart) / BOAT_WASH_LENGTH) / shape.z;
''',
     '''    float washLife = min(age / BOAT_WASH_LIFETIME, (fromBow - washStart) / BOAT_WASH_LENGTH) / washLength;
'''),
    ('''    float foamLine = hullEdge + alongHull * angleSlope + age * BOAT_WAKE_DRIFT * shape.x;
''',
     '''    float foamLine = hullEdge + alongHull * angleSlope + age * BOAT_WAKE_DRIFT * armAngle;
'''),
    ('''    // Fading out the same way as the ocean waves' foam
    if (noises.x < 0.0)
        noises.xy = vec2(foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime),
            noise(world * 1.3));
    float breakup = noises.x;
    float ragged = (noises.y - 0.5) * raggedness;
''',
     '''    // Fading out the same way as the ocean waves' foam
    if (!wakeNoise.foamReady) {
        wakeNoise.foamReady = true;
        wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
        wakeNoise.ragged = noise(world * 1.3);
    }
    float breakup = wakeNoise.breakup;
    float ragged = (wakeNoise.ragged - 0.5) * raggedness;
'''),
    ('''// Foam a hull pushed out, from 0 to 1, how far through its life it is,''',
     '''// The noise at a spot of water, shared by every boat wake there, each pair worked out the first time it's needed
struct WakeNoise {
    bool foamReady;
    float breakup; // which foam fades out first, as foamBreakupNoise, finer than the ocean waves'
    float ragged; // the foam's ragged edges, from 0 to 1
    bool fadeReady;
    float endRagged; // the tail's ragged end, from 0 to 1
    float stopBreakup; // which foam turns see-through first once the boat slows, as foamBreakupNoise
};

// Foam a hull pushed out, from 0 to 1, how far through its life it is,'''),
    ('''    // The noise here, worked out once for all the wakes, when first needed
    vec4 noises = vec4(-1.0);
''',
     '''    WakeNoise wakeNoise = WakeNoise(false, 0.0, 0.0, false, 0.0, 0.0);
'''),
    ('''        // Nothing left once even the newest foam has broken up
        if (hull.y >= BOAT_WAKE_STOP_TIME)
            continue;
''', ''),
    ('''        bool bigShip = size.w > 0.5;
        vec4 shape = bigShip ? vec4(BOAT_SHIP_ANGLE, BOAT_SHIP_LIFETIME, BOAT_SHIP_WASH_LENGTH, BOAT_SHIP_WASH_OFFSET) :
            vec4(1.0, 1.0, 1.0, 0.0);
        float washScale = bigShip ? BOAT_SHIP_WASH_WIDTH : 1.0;
''',
     '''        bool squareBow = size.z > 0.5;
        bool bigShip = size.w > 0.5;
'''),
    ('''        float forward = size.z > 0.5 ? 0.0 : (BOAT_REFERENCE_LENGTH - size.x) * BOAT_WAKE_SIZE_FORWARD;
''',
     '''        float forward = squareBow ? 0.0 : (BOAT_REFERENCE_LENGTH - size.x) * BOAT_WAKE_SIZE_FORWARD;
'''),
    ('''        bool firstSegment = true;
        int total = count + 2;
        vec4 stern = vec4(sternPos, 0.0, bowLength);
        vec4 a = vec4(bowPos, 0.0, 0.0);
        for (int i = 0; i < BOAT_WAKE_MAX_POINTS + 1; i++) {
            if (i + 1 >= total)
                break;
            vec4 b = stern;
''',
     '''        bool firstSegment = true;
        vec4 stern = vec4(sternPos, 0.0, bowLength);
        vec4 a = vec4(bowPos, 0.0, 0.0);
        // From the bow to the stern, then on to each point of the path
        for (int i = 0; i <= count; i++) {
            vec4 b = stern;
'''),
    ('''                    outside = (firstSegment && t <= 0.0) || (i + 2 == total && t >= 1.0);
''',
     '''                    outside = (firstSegment && t <= 0.0) || (i == count && t >= 1.0);
'''),
    ('''            age + hull.y * BOAT_WAKE_STOP_AGEING, hull.w < 0.5, size.z > 0.5, shape, washScale, noises);
''',
     '''            age + hull.y * BOAT_WAKE_STOP_AGEING, hull.w < 0.5, squareBow, bigShip, wakeNoise);
'''),
    ('''        // Any foam here means the breakup and raggedness are already worked out
        if (noises.z < 0.0)
            noises.zw = vec2(noise(world * 1.3 + 7.1),
                foamBreakupNoise(world * BOAT_WAKE_STOP_ALPHA_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime));
        float breakup = noises.x;
''',
     '''        // Any foam here means the foam noise is already worked out
        if (!wakeNoise.fadeReady) {
            wakeNoise.fadeReady = true;
            wakeNoise.endRagged = noise(world * 1.3 + 7.1);
            wakeNoise.stopBreakup =
                foamBreakupNoise(world * BOAT_WAKE_STOP_ALPHA_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
        }
        float breakup = wakeNoise.breakup;
'''),
    ('''        float endEdge = a.w - fromBow - noises.z * BOAT_WAKE_END_RAGGEDNESS;
''',
     '''        float endEdge = a.w - fromBow - wakeNoise.endRagged * BOAT_WAKE_END_RAGGEDNESS;
'''),
    ('''        float alpha = fadeIn * (1.0 - saturate(fadeOut * (1.0 + unevenness) - noises.w * unevenness));
''',
     '''        float alpha = fadeIn * (1.0 - saturate(fadeOut * (1.0 + unevenness) - wakeNoise.stopBreakup * unevenness));
'''),
]}
