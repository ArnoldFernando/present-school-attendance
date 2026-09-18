# Data model

Room database name: `attendance_fr.db`
Version: **1**
Export schema: `app/schemas/` (KSP `room.schemaLocation`)
Migrations: none. `fallbackToDestructiveMigration()` is enabled — bumping the version without a `Migration` **wipes** local data. Always backup first.

Type converters: `Converters` (FloatArray ↔ little-endian `ByteArray`). Embeddings are stored as `ByteArray` columns directly; the converter is available for any future `FloatArray` fields.

Foreign keys: ON, `onDelete = CASCADE` from `students` to embeddings and attendance.

---

## ER diagram

```
class_sections 1 ─── (logical, by name) ─── * students
students 1 ─── * face_embeddings
students 1 ─── * attendance_records     UNIQUE(studentId, date)
```

`class_sections.name` and `students.className` are **not** a foreign key. Classes can be renamed without rewriting every student; students can be given a class name that does not yet exist in `class_sections` (the enrollment form will add it). This is intentional for a single-teacher offline tablet.

---

## Entity: `class_sections`

Kotlin: `ClassSectionEntity`
Table: `class_sections`

| Column | SQLite | Kotlin | Notes |
| --- | --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT | `Long` | |
| `name` | TEXT NOT NULL | `String` | Unique index. Display name, e.g. `"7-A"`, `"Year 4 Blue"`. |

Indices: unique on `name`.

---

## Entity: `students`

Kotlin: `StudentEntity`
Table: `students`

| Column | SQLite | Kotlin | Notes |
| --- | --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT | `Long` | Internal FK target. Not the school roll number. |
| `studentId` | TEXT NOT NULL | `String` | Roll / admission number. **Unique**. |
| `name` | TEXT NOT NULL | `String` | Display name. |
| `className` | TEXT NOT NULL | `String` | Class / section label (see above). |
| `dateEnrolled` | INTEGER NOT NULL | `Long` | Epoch millis. |

Indices: unique on `studentId`; non-unique on `className`.

Domain mapping (`Student`) adds `embeddingCount` (not persisted) from a GROUP BY on `face_embeddings`.

---

## Entity: `face_embeddings`

Kotlin: `FaceEmbeddingEntity`
Table: `face_embeddings`

| Column | SQLite | Kotlin | Notes |
| --- | --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT | `Long` | |
| `studentId` | INTEGER NOT NULL | `Long` | FK → `students.id` ON DELETE CASCADE |
| `embeddingVector` | BLOB NOT NULL | `ByteArray` | Little-endian IEEE-754 floats. Length = `4 * D` where D is 128 or 512. |
| `dateAdded` | INTEGER NOT NULL | `Long` | Epoch millis. |

Indices: non-unique on `studentId`.

Codec: `EmbeddingCodec.toBytes` / `toFloats`. Equals/hashCode on the entity use `contentEquals` / `contentHashCode` because `ByteArray` is a Java array.

**No source photographs are stored.** A re-enroll deletes all rows for that student and inserts the new shots.

Typical cardinality: 3–5 rows per student (`EnrollViewModel.minShots = 3`, `maxShots = 5`). Matching uses the **max** cosine across a student’s rows.

---

## Entity: `attendance_records`

Kotlin: `AttendanceRecordEntity`
Table: `attendance_records`

| Column | SQLite | Kotlin | Notes |
| --- | --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT | `Long` | |
| `studentId` | INTEGER NOT NULL | `Long` | FK → `students.id` ON DELETE CASCADE |
| `date` | TEXT NOT NULL | `String` | Local calendar date, ISO-8601 `YYYY-MM-DD` (`DateUtils.today()`). |
| `timestamp` | INTEGER NOT NULL | `Long` | Epoch millis of the mark. |
| `status` | TEXT NOT NULL | `String` | `AttendanceStatus.name`: `Present`, `Absent`, `Late`, `ManualOverride`. |
| `matchConfidence` | REAL | `Float?` | Cosine similarity. **NULL** if marked by hand. |
| `isManual` | INTEGER NOT NULL | `Boolean` | `1` if a teacher overrode or assigned by hand. |

Indices:

- **UNIQUE** `(studentId, date)` — one row per student per local day. Automatic re-matches are ignored; a manual override **UPDATEs** this row.
- non-unique on `date`, `studentId`.

`AttendanceStatus.ManualOverride` is reserved; the current UI writes `Present` / `Late` / `Absent` with `isManual = true` instead, which is easier to report on. Both are valid in the column.

---

## Settings (not Room)

`DataStore` file `attendance_fr_settings`:

| Key | Type | Default | Range |
| --- | --- | --- | --- |
| `confidence_threshold` | float | `0.60` | clamped `[0.30, 0.95]` |
| `last_selected_class` | string | `""` | last class dropdown value |

---

## Backup file

Format: ZIP named `attendancefr-yyyyMMdd-HHmmss.afrbak`

Entries: `attendance_fr.db`, and `attendance_fr.db-wal` / `attendance_fr.db-shm` if present.

Produced after `PRAGMA wal_checkpoint(FULL)`. Restore replaces those files and requires a process restart (`BackupManager.NeedsRestart`).

Treat the file as **student PII** (names, roll numbers, face embeddings, attendance).

---

## Excel export (derived, not a table)

Apache POI `.xlsx` written under `filesDir/exports/`.

**Sheet `Records`**

| Student ID | Name | Class | Date | Time | Status | Match Confidence | Marked By |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `students.studentId` | `name` | `className` | `date` | `HH:mm:ss` from `timestamp` | Present / Late / Absent / ManualOverride | `%.3f` or blank | `Face match` / `Manual` / `Implied` |

Rows with Marked By = `Implied` are enrolled students with **no** row on a date that otherwise has attendance (they are exported as Absent so the workbook is a complete register).

**Sheet `Summary`**

| Student ID | Name | Class | Present | Late | Absent (implied) | Sessions | Attendance rate % |
| --- | --- | --- | --- | --- | --- | --- | --- |

`Sessions` = number of distinct dates in the export window that have at least one real record. Rate = `(Present + Late) / Sessions * 100`.

---

## Integrity rules enforced in code

- Enrolling a duplicate `studentId` throws `IllegalArgumentException` (`OnConflictStrategy.ABORT`).
- Empty class names are rejected.
- Automatic `mark()` with an existing `(studentId, date)` returns `AlreadyMarked` and does not overwrite.
- Manual `mark()` updates status, timestamp, and sets `isManual = true`.
- Deleting a student cascades embeddings and attendance.

---

## Sample rows

```
students
  id=1  studentId=2025-014  name=Amina Khan  className=7-A  dateEnrolled=1712908800000

face_embeddings
  id=10 studentId=1 embeddingVector=<512 bytes> dateAdded=1712908801000
  id=11 studentId=1 embeddingVector=<512 bytes> dateAdded=1712908804000
  id=12 studentId=1 embeddingVector=<512 bytes> dateAdded=1712908807000

attendance_records
  id=50 studentId=1 date=2025-04-12 timestamp=1712912400000 status=Present matchConfidence=0.734 isManual=0
```
