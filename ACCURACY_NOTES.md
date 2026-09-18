# Accuracy notes

Target: **≥ 90 % true-accept** under normal classroom lighting with a proper enrollment, at a default cosine-similarity threshold of **0.60**, with unknown / low-confidence faces **never silently assigned**.

This file explains what that number means, why 0.60 is the default, what will break it, and how to deploy so you actually hit it.

---

## 1. What “confidence” is

The app does **not** output a probability calibrated on a closed set. It outputs **cosine similarity** between two L2-normalized embeddings:

```
similarity = dot(probe, gallery_i)     # both unit-length, so this is cosine
best       = max similarity over that student's 3–5 enrollment shots
decision   = Present if best ≥ threshold, else Unknown
```

| Similarity | Typical meaning (MobileFaceNet, same camera, indoor) |
| --- | --- |
| 0.80 – 0.95 | Same person, similar pose and lighting to enrollment |
| 0.65 – 0.80 | Same person, moderate pose / expression change |
| 0.45 – 0.65 | Ambiguous: same person at a hard angle, **or** a lookalike |
| &lt; 0.45 | Different person in most galleries of classroom size |

The default **0.60** sits on the conservative side of the ambiguous band. That is deliberate: the product rule is *reliability over cleverness*. False rejects become “Unknown face — tap to review” and take two extra seconds of teacher time. False accepts silently put the wrong student on the roll.

Tune in **Settings → Match confidence threshold** (clamped 0.30–0.95).

---

## 2. How 0.60 was chosen

It is the value specified in the product brief and it matches published MobileFaceNet operating points for *verification* (not 1:N identification at airport scale):

- On LFW-style pairs, MobileFaceNet typically reports TAR &gt; 99 % at FAR 10⁻³ around cosine ~0.7 with aligned crops. Classroom 1:N against a few dozen students is a much easier gallery, so we can sit a little lower.
- We store **multiple shots** and take the **max**, which lifts genuine scores more than impostor scores.
- We reject bad stills *before* embedding (eyes closed, off-center, dark, blurry, multiple faces). That removes the left tail of genuine scores.

You should still **calibrate on your own device and classroom**:

1. Enroll 10 students with the production tablet, production lighting, production distance.
2. Capture 20 genuine stills and 20 impostor stills (other students).
3. Log similarities (the match confirmation already shows the score).
4. Pick the threshold that keeps false accepts at 0 in that sample, then add a small margin.

If you see false accepts, **raise** toward 0.70–0.75. If genuine students bounce to Unknown under the same lighting they enrolled in, **lower** toward 0.55 — but only after you have fixed enrollment quality.

---

## 3. Known limitations

These will defeat on-device MobileFaceNet. None of them are bugs in the matcher.

| Condition | Effect | Mitigation |
| --- | --- | --- |
| Strong backlight / window behind the student | Dark face, noisy embedding | Reposition tablet so light is on faces; enrollment gate already rejects mean luma &lt; 42 |
| Very low light / night classroom | Same | Turn lights on; do not lower the threshold to “make it work” |
| Extreme pose (full profile) | Genuine score drops below 0.60 | Enrollment includes left/right; attendance still wants a near-frontal shot |
| Motion blur | Garbage embedding | Hold still; sharpness gate on enrollment |
| Surgical mask, scarf, hand over mouth | Different visual domain | Manual override. Do not enroll masked faces unless *every* attendance shot will also be masked |
| Heavy sunglasses | Eyes-open classifier may fail closed; embeddings shift | Remove glasses for enrollment if they are not worn every day; otherwise enroll *with* the usual glasses |
| Large change in hairstyle / makeup / age (months) | Drift | Re-enroll at the start of term |
| Identical twins / siblings with very similar faces | Impostor scores can clear 0.60 | Raise threshold, enroll extra angles, prefer manual for that pair, never rely on FR alone |
| Different camera than enrollment | Domain shift | Enroll and take attendance on the **same device** |
| Photos of photos / phone screen | Often matches the person in the photo | Basic liveness (eyes-open + pose) is **not** anti-spoofing. For high-stakes use, add a dedicated liveness model (out of scope for v0.1.0) |
| Gallery of hundreds of students on one tablet | 1:N collision risk grows | Keep galleries to a class (≤ ~40) or raise the threshold and always offer manual |

ML Kit’s “eyes open” and head-angle checks are **liveness *signals***, not a PAD (presentation-attack detection) system. A printed photo held still with eyes visible can pass. Treat this app as a **speed-up for an honest classroom**, not as a security identity proof.

---

## 4. Recipe to hit 90 %+ in a real classroom

Follow all of these; skipping enrollment quality is the usual reason deployments miss the target.

### Enrollment

- Use the **same tablet** that will take attendance.
- Distance ~0.6–1.2 m, camera at **eye level**, face filling the on-screen guide.
- Even lighting on the face; no window directly behind the student.
- Capture the three prompted poses. Do not skip “turn slightly left/right”.
- Eyes open, neutral expression, glasses in the state the student will wear daily.
- If a shot is rejected, **fix the cause** (move, lighting, pose) — do not mash Capture until it happens to pass.
- Re-enroll after a haircut, new glasses, or the start of a new term.

### Capture station

- Mount or hold the tablet landscape or portrait consistently. A cheap stand beats a walking teacher.
- Point it down a well-lit aisle, not at a window.
- One student at a time in the frame. The app refuses multiple faces; don’t fight it with a group shot.
- Prefer the **Capture & match** button over Auto until you trust the room. Auto is a convenience and can fire on a passer-by.
- Keep the threshold at 0.60 until you have a week of scores to look at.

### Operational

- Always leave **Manual override** in the teacher’s muscle memory. Unknown and Absent are expected, not failures.
- Review the day’s roll before dismissing class (Reports or Manual). Implied absences are anyone not marked on a day that had at least one record.
- Export Excel weekly so a device loss is not a term’s worth of data. Also use Settings → Backup.
- Do not share the tablet across classes without filtering by class — matching is 1:N against **all** enrolled embeddings, not only the selected class. (The class dropdown is for teacher context and reports. Narrowing the gallery to the selected class is a straightforward enhancement; see the assumptions in the project summary.)

### Device

- Mid-range 2021 tablet or better, 3 GB RAM, front camera with autofocus if possible.
- Disable battery savers that throttle CPU while the app is foregrounded; TFLite needs those 4 threads for &lt;1 s matches.

---

## 5. What the quality gate already does

Before an embedding is computed, a still must pass:

1. Exactly one face.
2. Face short side ≥ 18 % of the image short side (move closer).
3. Face roughly centered.
4. Both eyes-open probabilities ≥ 0.35 (ML Kit).
5. Yaw within the pose window (tight on “look straight”, looser on left/right).
6. Pitch within ±20°.
7. Mean luma of the crop not &lt; 42 and not &gt; 230.
8. Laplacian variance ≥ 48 (blur).

Failures become a **user-facing sentence**, never a guessed identity.

---

## 6. Logging scores in the field

Every automatic Present row stores `matchConfidence` in Room and in the Excel “Match Confidence” column. After a week:

- Sort that column. Genuine Presents should cluster well above the threshold.
- If a cluster of genuines sits at 0.61–0.64, enrollment quality is marginal — re-enroll those students rather than dropping the threshold.
- Manual overrides have `null` confidence; they do not pollute the distribution.

There is no cloud dashboard. The workbook *is* the dashboard.

---

## 7. What v0.1.0 does not claim

- Not 99.9 % verification. Not NIST FRVT numbers.
- Not mask-robust, not IR, not 3D anti-spoof.
- Not fair across all demographics out of the box. If you deploy in a mixed-age, mixed-ethnicity school, **measure** FAR/FRR on *your* students. MobileFaceNet was trained on public face corpora that are not balanced.
- Not a legal time-clock. Keep the manual path and a paper fallback for disputes.

Used as specified — same device, decent light, 3–5 good enrollment shots, threshold 0.60, teacher in the loop for Unknown — 90 %+ on a single class is a reasonable expectation. Used as a magic unattended kiosk in a hallway, it is not.
