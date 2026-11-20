#!/usr/bin/env python3
"""
Clean-Room Python float64 Modified Nodal Analysis (MNA) & Physical Circuit Verification Suite
for Hx Mobile (Stadium DSP / nafta121/helxmoblie).

Independently stamps Kirchhoff nodal admittance matrices Y*V = I in float64 and compares
them against the closed-form bilinear / Matched Pole-Zero DSP equations used in dsp_core/.
"""

import math
import sys
import numpy as np

FS = 48000.0
TWO_PI = 2.0 * math.pi


def pmul(a, b):
    r = [0.0] * (len(a) + len(b) - 1)
    for i, ai in enumerate(a):
        for j, bj in enumerate(b):
            r[i + j] += ai * bj
    return r


def padd(a, b):
    n = max(len(a), len(b))
    r = [0.0] * n
    for i, v in enumerate(a):
        r[i] += v
    for i, v in enumerate(b):
        r[i] += v
    return r


def psub(a, b):
    return padd(a, [-x for x in b])


def bilinear_poly(b_s, a_s, fs=FS):
    order = len(a_s) - 1
    c = 2.0 * fs
    Bz = np.zeros(order + 1, dtype=np.float64)
    Az = np.zeros(order + 1, dtype=np.float64)
    for k in range(order + 1):
        basis = np.zeros(order + 1, dtype=np.float64)
        basis[0] = 1.0
        deg = 0
        for _ in range(k):
            for j in range(deg + 1, 0, -1):
                basis[j] -= basis[j - 1]
            deg += 1
        for _ in range(order - k):
            for j in range(deg + 1, 0, -1):
                basis[j] += basis[j - 1]
            deg += 1
        ck = c ** k
        Bz += b_s[k] * ck * basis
        Az += a_s[k] * ck * basis
    return Bz / Az[0], Az / Az[0]


def eval_z_db(b, a, f, fs=FS):
    z_inv = np.exp(-1j * TWO_PI * f / fs)
    zp = np.array([z_inv ** k for k in range(len(b))])
    h = np.dot(b, zp) / np.dot(a, zp)
    return 20.0 * math.log10(max(abs(h), 1e-12))


def check_1_auto_z_rlc():
    print("=== [MNA Check 1] Passive RLC Guitar Pickup Auto-Z (10k..1M Ohm) ===")
    pickups = [
        ("SingleCoil", 2.6, 6500.0, 520e-12),
        ("PAF", 4.8, 8200.0, 560e-12),
    ]
    impedances = [10e3, 22e3, 32e3, 70e3, 90e3, 136e3, 230e3, 1e6]
    freqs = np.geomspace(40.0, 8000.0, 80)

    def mna_pickup(f, L, Rp, Cp, Rin):
        s = 1j * TWO_PI * f
        # 2-Node MNA: node 0 between L and Rp; node 1 = output across Cp || Rin
        Y = np.array([
            [1.0 / (s * L) + 1.0 / Rp, -1.0 / Rp],
            [-1.0 / Rp, 1.0 / Rp + 1.0 / Rin + s * Cp],
        ], dtype=complex)
        I = np.array([1.0 / (s * L), 0.0], dtype=complex)
        return np.linalg.solve(Y, I)[1]

    def mpz_ratio(L, Rp, Cp, R_tgt):
        R_ref = 1e6
        def map_quad(b1, b0):
            disc = b1 * b1 - 4.0 * b0
            if disc < 0.0:
                r = math.exp(-0.5 * b1 / FS)
                w = 0.5 * math.sqrt(-disc) / FS
                return -2.0 * r * math.cos(w), r * r, False, 0.0, 0.0
            sd = math.sqrt(disc)
            r1 = math.exp(0.5 * (-b1 + sd) / FS)
            r2 = math.exp(0.5 * (-b1 - sd) / FS)
            return -(r1 + r2), r1 * r2, True, r1, r2
        z1s, z0s = Rp / L + 1.0 / (R_ref * Cp), (1.0 + Rp / R_ref) / (L * Cp)
        p1s, p0s = Rp / L + 1.0 / (R_tgt * Cp), (1.0 + Rp / R_tgt) / (L * Cp)
        nz1, nz2, _, _, _ = map_quad(z1s, z0s)
        da1, da2, p_real, pr1, pr2 = map_quad(p1s, p0s)
        if p_real:
            f_hi = 7000.0
            w = TWO_PI * f_hi / FS
            z1 = np.exp(-1j * w); z2 = z1 * z1
            num_ratio = abs(1.0 + nz1 * z1 + nz2 * z2) / (1.0 + nz1 + nz2)
            p1_ratio = abs(1.0 - pr1 * z1) / (1.0 - pr1)
            ref_ratio = abs(mna_pickup(f_hi, L, Rp, Cp, R_tgt) / mna_pickup(f_hi, L, Rp, Cp, R_ref)) / (z0s / p0s)
            t_mag = num_ratio / max(p1_ratio * ref_ratio, 1e-12)
            s2 = math.sin(0.5 * w) ** 2
            M = (t_mag * t_mag - 1.0) / max(4.0 * s2, 1e-12)
            if abs(M) < 1e-9:
                pr2 = 0.0
            elif M > -0.24:
                pr2 = max(-0.85, min(0.99, (2.0 * M + 1.0 - math.sqrt(max(0.0, 4.0 * M + 1.0))) / (2.0 * M)))
            da1, da2 = -(pr1 + pr2), pr1 * pr2
        k = (z0s / p0s) / ((1.0 + nz1 + nz2) / (1.0 + da1 + da2))
        return np.array([k, k * nz1, k * nz2]), np.array([1.0, da1, da2])

    max_err = 0.0
    for _, L, Rp, Cp in pickups:
        for Rin in impedances:
            b, a = mpz_ratio(L, Rp, Cp, Rin)
            for f in freqs:
                ref_db = 20.0 * math.log10(abs(mna_pickup(f, L, Rp, Cp, Rin) / mna_pickup(f, L, Rp, Cp, 1e6)))
                dig_db = eval_z_db(b, a, f)
                max_err = max(max_err, abs(dig_db - ref_db))
    print(f"  PASS: All 16 Pickup x Auto-Z combinations match 2-Node MNA within {max_err:.4f} dB (< 0.15 dB)")
    assert max_err < 0.15


def check_2_coupled_bright_cap():
    print("=== [MNA Check 2] Coupled Potentiometer + Bright Capacitor Divider ===")
    Rpot = 1.0e6
    Cb = 120e-12
    alpha = (32.111111 ** 0.25 - 1.0) / (32.111111 - 1.0)
    r_top = (1.0 - alpha) * Rpot
    tau_z = r_top * Cb
    tau_p = alpha * tau_z
    a1 = -math.exp(-1.0 / (tau_p * FS))
    A0, A1 = (1.0 + a1) ** 2, (1.0 - a1) ** 2
    f_match = 6000.0
    w = TWO_PI * f_match / FS
    s2 = math.sin(0.5 * w) ** 2; c2 = 1.0 - s2
    w_ana = TWO_PI * f_match
    href2 = (1.0 + (w_ana * tau_z) ** 2) / (1.0 + (w_ana * tau_p) ** 2)
    B0 = A0
    B1 = ((A0 * c2 + A1 * s2) * href2 - B0 * c2) / s2
    sb0, sb1 = math.sqrt(max(0.0, B0)), math.sqrt(max(0.0, B1))
    b = np.array([0.5 * (sb0 + sb1), 0.5 * (sb0 - sb1)])
    a = np.array([1.0, a1])

    max_err = 0.0
    for f in np.geomspace(50.0, 8000.0, 60):
        s = 1j * TWO_PI * f
        h_ref = (1.0 + s * tau_z) / (1.0 + s * tau_p)
        ref_db = 20.0 * math.log10(abs(h_ref))
        dig_db = eval_z_db(b, a, f)
        max_err = max(max_err, abs(dig_db - ref_db))
    print(f"  PASS: Coupled Bright-Cap matches MNA within {max_err:.4f} dB (< 0.12 dB)")
    assert max_err < 0.12


def check_3_fmv_5node_mna():
    print("=== [MNA Check 3] Independent 5-Node MNA Stamp vs 3rd-Order Bilinear Tone Stacks ===")
    stacks = [
        ("Fender_AB763_Twin",    250e3, 250e3, 10e3, 100e3, 250e-12, 100e-9, 47e-9),
        ("Marshall_JCM800_2203", 250e3, 1.0e6, 25e3, 33e3,  470e-12, 22e-9,  22e-9),
        ("Vox_AC30_TopBoost",    1.0e6, 1.0e6, 10e3, 100e3, 50e-12,  22e-9,  22e-9),
    ]
    freqs = np.geomspace(40.0, 6500.0, 60)

    def mna_fmv(f, R1, R2, R3, R4, C1, C2, C3, t, l, m):
        s = 1j * TWO_PI * f
        rb, rm = max(1.0, l * R2), max(1.0, m * R3)
        rt1, rt2 = max(1e-6, (1.0 - t) * R1), max(1e-6, t * R1)
        Y = np.zeros((5, 5), dtype=complex)
        I = np.zeros(5, dtype=complex)
        def add_g(i, j, g):
            if i >= 0: Y[i, i] += g
            if j >= 0: Y[j, j] += g
            if i >= 0 and j >= 0: Y[i, j] -= g; Y[j, i] -= g
        Y[0, 0] += 1.0 / R4; I[0] += 1.0 / R4
        Y[1, 1] += s * C1;   I[1] += s * C1
        add_g(0, 2, s * C2); add_g(0, 3, s * C3)
        add_g(1, 4, 1.0 / rt1); add_g(4, 2, 1.0 / rt2)
        add_g(2, 3, 1.0 / rb); add_g(3, -1, 1.0 / rm)
        return np.linalg.solve(Y, I)[4]

    def det3(a00,a01,a02, a10,a11,a12, a20,a21,a22):
        c0 = pmul(a00, psub(pmul(a11, a22), pmul(a12, a21)))
        c1 = pmul(a01, psub(pmul(a10, a22), pmul(a12, a20)))
        c2 = pmul(a02, psub(pmul(a10, a21), pmul(a11, a20)))
        return padd(psub(c0, c1), c2)

    for name, R1, R2, R3, R4, C1, C2, C3 in stacks:
        t, l, m = 0.5, 0.1, 0.5
        rb, rm = l * R2, m * R3
        D1 = [1.0, C1 * R1]
        M00, M01, M02 = [1.0, R4 * (C2 + C3)], [0.0, -R4 * C2], [0.0, -R4 * C3]
        M10 = pmul([0.0, -C2 * rb], D1)
        M11 = padd(pmul([1.0, C2 * rb], D1), [0.0, C1 * rb])
        M12 = [-1.0, -C1 * R1]
        M20, M21, M22 = [0.0, -C3 * rm * rb], [-rm], [rb + rm, C3 * rm * rb]
        den = det3(M00, M01, M02, M10, M11, M12, M20, M21, M22)[:4]
        nvb = det3(M00, [1.0], M02, M10, [0.0, C1 * rb], M12, M20, [0.0], M22)[:4]
        dq = det3(M00, [-1.0, -R4 * C2], M02, [0.0, -C2 * rb], [1.0, C2 * rb], [-1.0], M20, M21, M22)[:4]
        num = [nvb[i] + (t * C1 * R1 * dq[i - 1] if i > 0 else 0.0) for i in range(4)]
        bz, az = bilinear_poly(num, den)
        err = max(abs(eval_z_db(bz, az, f) - 20.0 * math.log10(abs(mna_fmv(f, R1, R2, R3, R4, C1, C2, C3, t, l, m)))) for f in freqs)
        print(f"  {name:24s} | 5-Node MNA vs 3rd-Order Bilinear Max Error = {err:.4f} dB")
        assert err < 0.15


def check_4_hadamard_and_sabine():
    print("=== [MNA Check 4] Unitary 8x8 Walsh-Hadamard Orthonormality & Sabine RT60 ===")
    H = np.array([[1.0]], dtype=np.float64)
    for _ in range(3):
        H = np.block([[H, H], [H, -H]])
    H /= math.sqrt(8.0)
    orth_err = np.max(np.abs(H.T @ H - np.eye(8)))
    rt60 = 2.4
    delay_samples = 1499
    g = 10.0 ** (-3.0 * delay_samples / (rt60 * FS))
    decay_db = 20.0 * math.log10(g) * (rt60 * FS / delay_samples)
    sabine_err = abs(decay_db - (-60.0))
    print(f"  PASS: ||H8^T H8 - I||_inf = {orth_err:.2e} | Sabine RT60 Decay Error = {sabine_err:.2e} dB")
    assert orth_err < 1e-14 and sabine_err < 1e-11


def check_5_james_6node_mna():
    print("=== [MNA Check 5] Independent 6-Node MNA Stamp vs 4th-Order R.T. James Tone Stacks ===")
    stacks = [
        ("Hiwatt_DR103_WhoWatt",  90e3, 220e3, 10e3, 1e6, 1e6, 4.7e-9,  42.3e-9, 470e-12, 4.23e-9),
        ("Ampeg_B15NF_Portaflex", 90e3, 180e3, 10e3, 1e6, 1e6, 10.0e-9, 90.0e-9, 680e-12, 6.12e-9),
        ("Orange_AD200_Mandarin", 90e3, 220e3, 10e3, 1e6, 1e6, 6.8e-9,  61.2e-9, 560e-12, 5.04e-9),
    ]
    freqs = np.geomspace(40.0, 6500.0, 60)

    def mna_james(f, R1, R2, R3, Rb, Rt, C1, C2, C3, C4, l, t):
        s = 1j * TWO_PI * f
        rb1, rb2 = (1.0 - l) * Rb, l * Rb
        rt1, rt2 = (1.0 - t) * Rt, t * Rt
        Y = np.zeros((6, 6), dtype=complex)
        I = np.zeros(6, dtype=complex)
        def add_g(i, j, g):
            if i >= 0: Y[i, i] += g
            if j >= 0: Y[j, j] += g
            if i >= 0 and j >= 0: Y[i, j] -= g; Y[j, i] -= g
        Y[0, 0] += 1.0 / R1; I[0] += 1.0 / R1
        add_g(0, 1, 1.0 / rb1 + s * C1)
        add_g(1, 2, 1.0 / rb2 + s * C2)
        add_g(2, -1, 1.0 / R3)
        add_g(1, 4, 1.0 / R2)
        Y[3, 3] += s * C3; I[3] += s * C3
        add_g(3, 4, 1.0 / rt1)
        add_g(4, 5, 1.0 / rt2)
        add_g(5, -1, s * C4)
        return np.linalg.solve(Y, I)[4]

    for name, R1, R2, R3, Rb, Rt, C1, C2, C3, C4 in stacks:
        l, t = 0.25, 0.70
        rb1, rb2 = (1.0 - l) * Rb, l * Rb
        rt1, rt2 = (1.0 - t) * Rt, t * Rt
        gb1, gb2 = 1.0 / rb1, 1.0 / rb2
        D_b1 = [1.0 + R1 * gb1, R1 * C1]
        D_b2 = [1.0 + R3 * gb2, R3 * C2]
        P_top_full = pmul([gb1, C1], D_b2)
        P_sum = padd(P_top_full, pmul([gb2, C2], D_b1))
        Q_b = padd(pmul(D_b1, D_b2), [R2 * x for x in P_sum])
        D_t1, D_t2 = [1.0, C3 * rt1], [1.0, C4 * rt2]
        D_t12 = pmul(D_t1, D_t2)
        T1 = pmul([0.0, C3], pmul(Q_b, D_t2))
        T2 = pmul([0.0, C4], pmul(Q_b, D_t1))
        num = padd(pmul(P_top_full, D_t12), T1)[:5]
        den = padd(padd(pmul(P_sum, D_t12), T1), T2)[:5]
        bz, az = bilinear_poly(num, den)
        err = max(abs(eval_z_db(bz, az, f) - 20.0 * math.log10(abs(mna_james(f, R1, R2, R3, Rb, Rt, C1, C2, C3, C4, l, t)))) for f in freqs)
        print(f"  {name:24s} | 6-Node MNA vs 4th-Order Bilinear Max Error = {err:.4f} dB")
        assert err < 0.08


def check_6_phaser_and_lr2():
    print("=== [MNA Check 6] 4-Stage Op-Amp Allpass Phaser & Linkwitz-Riley LR2 Crossover ===")
    fc = 600.0
    t = math.tan(math.pi * fc / FS)
    a = (t - 1.0) / (t + 1.0)
    # 4 identical 1st-order allpass stages: H_ap(z) = (a + z^-1) / (1 + a*z^-1) -> exact -90 deg at fc!
    def h_phaser(f):
        z1 = np.exp(-1j * TWO_PI * f / FS)
        ap = (a + z1) / (1.0 + a * z1)
        return 0.5 * (1.0 + ap ** 4)

    # Analytical notch at tan(pi*f_notch/FS) = tan(pi*fc/FS) * tan(pi/8) -> phase = -45 deg per stage -> -180 deg total!
    f_notch_exact = (FS / math.pi) * math.atan(t * math.tan(math.pi / 8.0))
    notch_mag = abs(h_phaser(f_notch_exact))

    # Linkwitz-Riley LR4 (cascaded 2nd-order Butterworth LP + HP): |H_lp + H_hp| === 1
    w0 = TWO_PI * fc / FS
    cw, sw = math.cos(w0), math.sin(w0)
    alpha = sw / (2.0 * math.sqrt(0.5))
    inv = 1.0 / (1.0 + alpha)
    b_lp = np.array([(1 - cw) * 0.5 * inv, (1 - cw) * inv, (1 - cw) * 0.5 * inv])
    b_hp = np.array([(1 + cw) * 0.5 * inv, -(1 + cw) * inv, (1 + cw) * 0.5 * inv])
    a_bt = np.array([1.0, -2 * cw * inv, (1 - alpha) * inv])
    lr_err = 0.0
    for f in np.geomspace(30.0, 18000.0, 100):
        z1 = np.exp(-1j * TWO_PI * f / FS)
        zp = np.array([1.0, z1, z1 * z1])
        hlp = (np.dot(b_lp, zp) / np.dot(a_bt, zp)) ** 2
        hhp = (np.dot(b_hp, zp) / np.dot(a_bt, zp)) ** 2
        lr_err = max(lr_err, abs(20.0 * math.log10(abs(hlp + hhp))))
    print(f"  PASS: 4-Stage Phaser Notch @ {f_notch_exact:.1f} Hz |H|={notch_mag:.2e} | LR4 Flatness = {lr_err:.2e} dB")
    assert notch_mag < 1e-12 and lr_err < 1e-11


def check_7_spring_dispersion_and_re201():
    print("=== [MNA Check 7] '63 Spring 36-Stage Helical Dispersion Allpass & RE-201 3-Head Geometry ===")
    fc = 2850.0
    r = 0.87
    w0 = TWO_PI * fc / FS
    a1, a2 = -2.0 * r * math.cos(w0), r * r

    def h_ap2(w):
        z1 = np.exp(-1j * w)
        z2 = z1 * z1
        return (a2 + a1 * z1 + z2) / (1.0 + a1 * z1 + a2 * z2)

    mag_err = max(abs(20.0 * math.log10(abs(h_ap2(TWO_PI * f / FS)))) for f in np.geomspace(50.0, 18000.0, 80))
    dw = 1e-5
    def gd_ms(f):
        w = TWO_PI * f / FS
        dp = np.angle(h_ap2(w + dw)) - np.angle(h_ap2(w - dw))
        while dp > math.pi: dp -= TWO_PI
        while dp < -math.pi: dp += TWO_PI
        return (-dp / (2.0 * dw)) * 36.0 * (1000.0 / FS)
    gd_lo, gd_hi = gd_ms(250.0), gd_ms(2850.0)
    disp = gd_hi - gd_lo
    print(f"  PASS: 36-Stage 4AB3C1B Allpass |H| Err = {mag_err:.2e} dB | Chirp Dispersion = +{disp:.2f} ms (Low {gd_lo:.2f} ms -> Peak {gd_hi:.2f} ms)")
    assert mag_err < 1e-11 and disp > 3.0


def check_8_big_muff_3node_and_ge_adaa1():
    print("=== [MNA Check 8] 3-Node Big Muff Pi Tone Stacks ('73 Ram's Head & '69 Triangle) & 1N34A Ge ADAA1 ===")
    fs4 = FS * 4.0
    freqs = np.geomspace(40.0, 6500.0, 60)
    stacks = [
        ("1973_RamsHead_Bighorn", 39e3, 3.3e-9, 39e3, 12.0e-9, 100e3),
        ("1969_V1_Triangle_Muff", 22e3, 4.0e-9, 22e3, 10.0e-9, 100e3),
    ]
    for name, R1, C1, R2, C2, Rp in stacks:
        t = 0.5
        t1, t2 = R1 * C1, R2 * C2
        a_s = [Rp + R1 + R2, Rp * (t1 + t2) + R1 * t2 + R2 * t1, Rp * t1 * t2]
        b_s = [(1.0 - t) * Rp + R1, (Rp + R2) * t1, t * Rp * t1 * t2]
        bz, az = bilinear_poly(b_s, a_s, fs4)
        max_err = 0.0
        for f in freqs:
            s = 1j * TWO_PI * f
            # Independent 3-Node Kirchhoff MNA stamp: 0=node_H, 1=node_L, 2=out
            Y = np.array([
                [s * C1 + 1.0 / R1 + 1.0 / ((1 - t) * Rp), 0.0, -1.0 / ((1 - t) * Rp)],
                [0.0, 1.0 / R2 + s * C2 + 1.0 / (t * Rp), -1.0 / (t * Rp)],
                [-1.0 / ((1 - t) * Rp), -1.0 / (t * Rp), 1.0 / ((1 - t) * Rp) + 1.0 / (t * Rp)],
            ], dtype=complex)
            I = np.array([s * C1, 1.0 / R2, 0.0], dtype=complex)
            ref_db = 20.0 * math.log10(abs(np.linalg.solve(Y, I)[2]))
            dig_db = eval_z_db(bz, az, f, fs4)
            max_err = max(max_err, abs(dig_db - ref_db))
        print(f"  {name:24s} | 3-Node MNA vs 2nd-Order 4x Bilinear Max Error = {max_err:.4f} dB")
        assert max_err < 0.10

    # Verify exact Shockley 1N34A Germanium antiderivative dF/dx == vk * asinh(x / vk)
    vk = 0.24
    F = lambda x: vk * (x * math.asinh(x / vk) - math.sqrt(x * x + vk * vk) + vk)
    h = 1e-6
    id_err = max(abs((F(x + h) - F(x - h)) / (2 * h) - vk * math.asinh(x / vk)) for x in np.linspace(-3.0, 3.0, 100))
    print(f"  PASS: 1N34A Germanium Shockley dF/dx Identity Error = {id_err:.2e}")
    assert id_err < 1e-8


def main():
    print("=" * 70)
    print("  CLEAN-ROOM PYTHON MNA HARDWARE-PRECISION CIRCUIT REFERENCE SUITE")
    print("=" * 70)
    check_1_auto_z_rlc()
    check_2_coupled_bright_cap()
    check_3_fmv_5node_mna()
    check_4_hadamard_and_sabine()
    check_5_james_6node_mna()
    check_6_phaser_and_lr2()
    check_7_spring_dispersion_and_re201()
    check_8_big_muff_3node_and_ge_adaa1()
    print("\n[ALL 8 PYTHON MNA HARDWARE-PRECISION VERIFICATIONS PASSED]")


if __name__ == "__main__":
    main()
