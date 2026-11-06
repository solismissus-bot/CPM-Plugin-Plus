package com.tom.cpm.blockbench.tools.anim;

/**
 * CPM+ 'Física / seguir o movimento': the math only (no Blockbench calls).
 * <p>
 * Every simulated part ("link") gets a particle at its tip. The particle is pulled toward where the driving animation
 * puts that tip (a damped spring, Verlet integration at 240 Hz), keeps its distance from the link's pivot and never
 * leaves the goal direction by more than the maximum angle. A link whose parent is simulated too hangs from the
 * parent's SIMULATED tip and its goal turns with the parent's offset, so a chain whips (each link lags its parent).
 * The offset of each link is a swing (no twist) in world space; {@link #localDelta} turns it into the Euler values an
 * additive animation must add to the part's own rotation (Blockbench order ZYX, the same per-axis sum Blockbench's
 * preview and CPM's additive animations do).
 * <p>
 * Units: positions in model pixels, time in seconds, angles in radians (degrees only in the output).
 */
final class PhysicsSim {
	private PhysicsSim() {
	}

	static final int SIM_HZ = 240;
	/** numbers per link and frame in a sampled pose: pivot 3, tip 3, world quat 4, parent world quat 4, local euler 3 */
	static final int STRIDE = 17;
	static final int O_PIVOT = 0, O_TIP = 3, O_WQ = 6, O_PQ = 10, O_EULER = 14;

	/** one sampled pose of the driving animation, every link (STRIDE numbers each) */
	static final class Frame {
		final double[] d;

		Frame(double[] d) {
			this.d = d;
		}

		static Frame lerp(Frame a, Frame b, double f, int links) {
			double[] o = new double[a.d.length];
			for (int i = 0; i < links; i++) {
				int b0 = i * STRIDE;
				for (int k = 0; k < 6; k++)o[b0 + k] = a.d[b0 + k] + (b.d[b0 + k] - a.d[b0 + k]) * f;
				nlerp(a.d, b.d, b0 + O_WQ, o, f);
				nlerp(a.d, b.d, b0 + O_PQ, o, f);
				for (int k = 0; k < 3; k++)o[b0 + O_EULER + k] = a.d[b0 + O_EULER + k] + (b.d[b0 + O_EULER + k] - a.d[b0 + O_EULER + k]) * f;
			}
			return new Frame(o);
		}

		private static void nlerp(double[] a, double[] b, int o, double[] out, double f) {
			double dot = a[o] * b[o] + a[o + 1] * b[o + 1] + a[o + 2] * b[o + 2] + a[o + 3] * b[o + 3];
			double s = dot < 0 ? -1 : 1;
			double l = 0;
			for (int k = 0; k < 4; k++) {
				out[o + k] = a[o + k] + (s * b[o + k] - a[o + k]) * f;
				l += out[o + k] * out[o + k];
			}
			l = Math.sqrt(l);
			if (l < 1e-12) {
				out[o] = out[o + 1] = out[o + 2] = 0;
				out[o + 3] = 1;
			} else for (int k = 0; k < 4; k++)out[o + k] /= l;
		}
	}

	/** simulation settings in physical units */
	static final class Params {
		/** natural frequency of the spring (rad/s): high = stiff */
		double omega = 15;
		/** damping ratio: 1 = no overshoot, low = bouncy */
		double zeta = 0.3;
		/** 0..1: the tip of a chain gets up to 60 % softer */
		double tipLoose = 0.3;
		/** px/s^2 downwards */
		double gravity;
		/** rad */
		double maxAngle = Math.toRadians(40);
		/** constant acceleration (px/s^2) from the game's own movement (wind while running, falling...) */
		double[] wind = new double[3];
		/** extra vertical acceleration for the first impulseTime seconds of the cycle (jump) */
		double impulseY, impulseTime;
	}

	/** what one run produced */
	static final class Out {
		/** [sample][link * 3] Euler deltas in degrees */
		double[][] deltas;
		/** [sample][link * 3] simulated tip positions (for the self-check) */
		double[][] tips;
		int cycles;
		boolean converged;
		/** largest change of any value between the last two cycles (degrees); 0 for one-shot */
		double lastDiff;
	}

	/**
	 * Runs the simulation over the sampled frames.
	 * @param parent parent link index per link (-1 = hangs from an animated, not simulated part); parents first
	 * @param depth depth of each link in its chain (0 = first link)
	 * @param frames loop: frame s at s * period / S (S frames, periodic); one-shot: S frames from 0 to period
	 */
	static Out run(int[] parent, int[] depth, Frame[] frames, boolean loop, double period, Params p, int maxCycles, double convergeDeg) {
		int n = parent.length, S = frames.length;
		Out out = new Out();
		out.deltas = new double[S][n * 3];
		out.tips = new double[S][n * 3];
		if (n == 0 || S < 2)return out;
		double dtSample = loop ? period / S : period / (S - 1);
		int sub = Math.max(1, (int) Math.round(dtSample * SIM_HZ));
		double dt = dtSample / sub;
		int maxDepth = 0;
		for (int dd : depth)maxDepth = Math.max(maxDepth, dd);
		double[] k = new double[n], keep = new double[n];
		for (int i = 0; i < n; i++) {
			double w = p.omega * (1 - 0.6 * p.tipLoose * (maxDepth > 0 ? depth[i] / (double) maxDepth : 0));
			w = Math.max(0.5, w);
			k[i] = w * w * dt * dt;
			keep[i] = Math.max(0, 1 - 2 * p.zeta * w * dt);
		}
		State st = new State(n);
		st.init(parent, frames[0]);
		if (loop) {
			double[][] prev = null, older = null;
			int c;
			for (c = 0; c < Math.max(1, maxCycles); c++) {
				double[][] cur = new double[S][n * 3];
				double[][] tips = new double[S][n * 3];
				for (int s = 0; s < S; s++) {
					Frame a = frames[s], b = frames[(s + 1) % S];
					for (int j = 1; j <= sub; j++) {
						double f = j / (double) sub;
						double time = (s + f) * dtSample;
						st.step(parent, a, b, f, dt, k, keep, p, time);
					}
					int r = (s + 1) % S;
					st.record(parent, frames[r], cur[r], tips[r]);
				}
				out.deltas = cur;
				out.tips = tips;
				if (prev != null) {
					double diff = maxDiff(prev, cur);
					out.lastDiff = diff;
					if (diff < convergeDeg) {
						out.converged = true;
						c++;
						break;
					}
				}
				older = prev;
				prev = cur;
			}
			out.cycles = c;
			if (!out.converged && older != null) {
				// the last cycle still differs from the one before: shift the start of the cycle so it continues the end
				// (cur[0] is recorded at the END of the cycle, cur[1..] followed the start of the cycle = older[0])
				int m = Math.max(1, S / 6);
				double[] jump = new double[n * 3];
				for (int i = 0; i < n * 3; i++)jump[i] = out.deltas[0][i] - older[0][i];
				for (int s = 1; s < m; s++) {
					double w = 1 - s / (double) m;
					for (int i = 0; i < n * 3; i++)out.deltas[s][i] += jump[i] * w;
				}
			}
		} else {
			st.record(parent, frames[0], out.deltas[0], out.tips[0]);
			for (int s = 0; s < S - 1; s++) {
				for (int j = 1; j <= sub; j++) {
					double f = j / (double) sub;
					st.step(parent, frames[s], frames[s + 1], f, dt, k, keep, p, (s + f) * dtSample);
				}
				st.record(parent, frames[s + 1], out.deltas[s + 1], out.tips[s + 1]);
			}
			out.cycles = 1;
			out.converged = true;
		}
		return out;
	}

	static double maxDiff(double[][] a, double[][] b) {
		double m = 0;
		for (int s = 0; s < a.length; s++)for (int i = 0; i < a[s].length; i++)m = Math.max(m, Math.abs(a[s][i] - b[s][i]));
		return m;
	}

	/** particles, previous positions, simulated bases and the world offset (swing) of every link */
	static final class State {
		final int n;
		final double[] x, xp, base, q;

		State(int n) {
			this.n = n;
			x = new double[n * 3];
			xp = new double[n * 3];
			base = new double[n * 3];
			q = new double[n * 4];
			for (int i = 0; i < n; i++)q[i * 4 + 3] = 1;
		}

		void init(int[] parent, Frame f) {
			double[] g = new double[3];
			for (int i = 0; i < n; i++) {
				int o = i * STRIDE;
				double[] P = vec(f.d, o + O_PIVOT), a = sub(vec(f.d, o + O_TIP), P);
				double[] b = parent[i] >= 0 ? add(vec(base, parent[i] * 3), rot(qi(q, parent[i]), sub(P, vec(f.d, parent[i] * STRIDE + O_PIVOT)))) : P;
				double[] qp = parent[i] >= 0 ? qi(q, parent[i]) : ID;
				double[] ga = rot(qp, a);
				for (int c = 0; c < 3; c++) {
					g[c] = b[c] + ga[c];
					x[i * 3 + c] = g[c];
					xp[i * 3 + c] = g[c];
					base[i * 3 + c] = b[c];
				}
				System.arraycopy(qp, 0, q, i * 4, 4);
			}
		}

		void step(int[] parent, Frame A, Frame B, double f, double dt, double[] k, double[] keep, Params p, double time) {
			double ax = p.wind[0], ay = p.wind[1] - p.gravity + (time < p.impulseTime ? p.impulseY : 0), az = p.wind[2];
			double dt2 = dt * dt;
			for (int i = 0; i < n; i++) {
				int o = i * STRIDE;
				double[] P = lerp3(A.d, B.d, o + O_PIVOT, f);
				double[] a = sub(lerp3(A.d, B.d, o + O_TIP, f), P);
				double L = len(a);
				double[] b, qp;
				int j = parent[i];
				if (j >= 0) {
					double[] Pj = lerp3(A.d, B.d, j * STRIDE + O_PIVOT, f);
					qp = qi(q, j);
					b = add(vec(base, j * 3), rot(qp, sub(P, Pj)));
				} else {
					qp = ID;
					b = P;
				}
				int x0 = i * 3;
				for (int c = 0; c < 3; c++)base[x0 + c] = b[c];
				if (L < 1e-6) {
					for (int c = 0; c < 3; c++)x[x0 + c] = xp[x0 + c] = b[c];
					System.arraycopy(qp, 0, q, i * 4, 4);
					continue;
				}
				double[] ga = rot(qp, a);
				double[] g = add(b, ga);
				double[] acc = {ax, ay, az};
				for (int c = 0; c < 3; c++) {
					double v = (x[x0 + c] - xp[x0 + c]) * keep[i];
					xp[x0 + c] = x[x0 + c];
					x[x0 + c] += v + (g[c] - x[x0 + c]) * k[i] + acc[c] * dt2;
				}
				double[] d = sub(vec(x, x0), b);
				double dl = len(d);
				double[] gdir = scale(ga, 1 / L);
				double[] dir = dl < 1e-9 ? gdir : scale(d, 1 / dl);
				double cos = Math.max(-1, Math.min(1, dot(dir, gdir)));
				double ang = Math.acos(cos);
				if (ang > p.maxAngle && ang > 1e-9)dir = slerpDir(gdir, dir, p.maxAngle / ang);
				for (int c = 0; c < 3; c++)x[x0 + c] = b[c] + dir[c] * L;
				double[] qi = mul(fromTo(gdir, dir), qp);
				System.arraycopy(qi, 0, q, i * 4, 4);
			}
		}

		/** Euler deltas (degrees) and simulated tips at a sampled frame */
		void record(int[] parent, Frame f, double[] deltas, double[] tips) {
			for (int i = 0; i < n; i++) {
				int o = i * STRIDE;
				double[] qp = parent[i] >= 0 ? qi(q, parent[i]) : ID;
				double[] dl = localDelta(qat(f.d, o + O_PQ), qp, qi(q, i), qat(f.d, o + O_WQ), vec(f.d, o + O_EULER));
				for (int c = 0; c < 3; c++) {
					deltas[i * 3 + c] = Math.toDegrees(dl[c]);
					tips[i * 3 + c] = x[i * 3 + c];
				}
			}
		}
	}

	/**
	 * Euler values (radians, order ZYX) to ADD to the part's local rotation so it ends up rotated by its offset.
	 * World rotation of the part = qi * w (w = animated world rotation), of its parent = qp * pw, so the local
	 * rotation is conj(pw) * conj(qp) * qi * w; returned: that rotation as ZYX Euler (the equivalent nearest to
	 * the current local Euler e) minus e.
	 */
	static double[] localDelta(double[] pw, double[] qp, double[] qi, double[] w, double[] e) {
		double[] l = mul(mul(mul(conj(pw), conj(qp)), qi), w);
		double[] t = eulerZYX(l);
		double[] best = nearest(t, e);
		return new double[] {best[0] - e[0], best[1] - e[1], best[2] - e[2]};
	}

	// ====================================================================== small vector / quaternion helpers (x, y, z, w)

	static final double[] ID = {0, 0, 0, 1};

	static double[] vec(double[] a, int o) {
		return new double[] {a[o], a[o + 1], a[o + 2]};
	}

	static double[] qat(double[] a, int o) {
		return new double[] {a[o], a[o + 1], a[o + 2], a[o + 3]};
	}

	/** quaternion of link i in a flat array of 4 per link */
	static double[] qi(double[] a, int i) {
		return qat(a, i * 4);
	}

	static double[] lerp3(double[] a, double[] b, int o, double f) {
		return new double[] {a[o] + (b[o] - a[o]) * f, a[o + 1] + (b[o + 1] - a[o + 1]) * f, a[o + 2] + (b[o + 2] - a[o + 2]) * f};
	}

	static double[] add(double[] a, double[] b) {
		return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
	}

	static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	static double[] scale(double[] a, double s) {
		return new double[] {a[0] * s, a[1] * s, a[2] * s};
	}

	static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	static double len(double[] a) {
		return Math.sqrt(dot(a, a));
	}

	/** Hamilton product a * b (THREE.Quaternion.multiplyQuaternions) */
	static double[] mul(double[] a, double[] b) {
		return new double[] {
				a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
				a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
				a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
				a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
	}

	static double[] conj(double[] q) {
		return new double[] {-q[0], -q[1], -q[2], q[3]};
	}

	/** v rotated by the unit quaternion q */
	static double[] rot(double[] q, double[] v) {
		double[] u = {q[0], q[1], q[2]};
		double[] t = scale(cross(u, v), 2);
		return add(add(v, scale(t, q[3])), cross(u, t));
	}

	/** the shortest rotation taking the unit vector u to the unit vector v */
	static double[] fromTo(double[] u, double[] v) {
		double d = dot(u, v);
		if (d > 1 - 1e-12)return new double[] {0, 0, 0, 1};
		if (d < -1 + 1e-9) {
			double[] axis = cross(new double[] {1, 0, 0}, u);
			if (len(axis) < 1e-6)axis = cross(new double[] {0, 1, 0}, u);
			axis = scale(axis, 1 / len(axis));
			return new double[] {axis[0], axis[1], axis[2], 0};
		}
		double[] c = cross(u, v);
		double[] q = {c[0], c[1], c[2], 1 + d};
		double l = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
		return new double[] {q[0] / l, q[1] / l, q[2] / l, q[3] / l};
	}

	/** unit vector between the unit vectors a (f = 0) and b (f = 1) on the great circle */
	static double[] slerpDir(double[] a, double[] b, double f) {
		double cos = Math.max(-1, Math.min(1, dot(a, b)));
		double th = Math.acos(cos);
		if (th < 1e-9)return new double[] {b[0], b[1], b[2]};
		double s = Math.sin(th);
		if (s < 1e-9) {
			// opposite: rotate a around any axis
			double[] q = fromTo(a, b);
			double half = Math.acos(Math.max(-1, Math.min(1, q[3])));
			double nh = half * f;
			double sh = Math.sin(half) > 1e-9 ? Math.sin(nh) / Math.sin(half) : 0;
			return rot(new double[] {q[0] * sh, q[1] * sh, q[2] * sh, Math.cos(nh)}, a);
		}
		double wa = Math.sin((1 - f) * th) / s, wb = Math.sin(f * th) / s;
		double[] r = add(scale(a, wa), scale(b, wb));
		double l = len(r);
		return l > 1e-12 ? scale(r, 1 / l) : new double[] {b[0], b[1], b[2]};
	}

	/** THREE.Euler.setFromQuaternion(q, 'ZYX'): rotation = Rz * Ry * Rx */
	static double[] eulerZYX(double[] q) {
		double x = q[0], y = q[1], z = q[2], w = q[3];
		double x2 = x + x, y2 = y + y, z2 = z + z;
		double xx = x * x2, xy = x * y2, xz = x * z2, yy = y * y2, yz = y * z2, zz = z * z2, wx = w * x2, wy = w * y2, wz = w * z2;
		double m11 = 1 - (yy + zz), m12 = xy - wz;
		double m21 = xy + wz, m22 = 1 - (xx + zz);
		double m31 = xz - wy, m32 = yz + wx, m33 = 1 - (xx + yy);
		double ey = Math.asin(-Math.max(-1, Math.min(1, m31)));
		double ex, ez;
		if (Math.abs(m31) < 0.9999999) {
			ex = Math.atan2(m32, m33);
			ez = Math.atan2(m21, m11);
		} else {
			ex = 0;
			ez = Math.atan2(-m12, m22);
		}
		return new double[] {ex, ey, ez};
	}

	/** THREE.Quaternion.setFromEuler(x, y, z, 'ZYX') */
	static double[] quatZYX(double ex, double ey, double ez) {
		double c1 = Math.cos(ex / 2), c2 = Math.cos(ey / 2), c3 = Math.cos(ez / 2);
		double s1 = Math.sin(ex / 2), s2 = Math.sin(ey / 2), s3 = Math.sin(ez / 2);
		return new double[] {
				s1 * c2 * c3 - c1 * s2 * s3,
				c1 * s2 * c3 + s1 * c2 * s3,
				c1 * c2 * s3 - s1 * s2 * c3,
				c1 * c2 * c3 + s1 * s2 * s3};
	}

	/** the ZYX Euler triple equal to t (t itself or (x+pi, pi-y, z+pi), each axis +-2pi) closest to e */
	static double[] nearest(double[] t, double[] e) {
		double[][] cands = {t, {t[0] + Math.PI, Math.PI - t[1], t[2] + Math.PI}};
		double[] best = null;
		double bestScore = Double.MAX_VALUE;
		for (double[] c : cands) {
			double[] w = new double[3];
			double score = 0;
			for (int i = 0; i < 3; i++) {
				w[i] = e[i] + wrapPi(c[i] - e[i]);
				score = Math.max(score, Math.abs(w[i] - e[i]));
			}
			if (score < bestScore - 1e-12) {
				bestScore = score;
				best = w;
			}
		}
		return best;
	}

	static double wrapPi(double a) {
		double r = a % (2 * Math.PI);
		if (r > Math.PI)r -= 2 * Math.PI;
		if (r < -Math.PI)r += 2 * Math.PI;
		return r;
	}
}
