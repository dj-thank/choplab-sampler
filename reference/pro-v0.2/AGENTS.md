# Pro reference preservation rules

This directory is historical, incomplete reference material. Do not treat it as the active build target.

- Do not bulk-copy these files into `app/`.
- Do not edit these files while implementing production code unless the task is specifically to annotate or repair the reference archive.
- Reconstruct interfaces under the root Gradle project in small compiling steps.
- Verify algorithms, JNI signatures, bounds and thread safety before reuse.
- Keep a record in the active ExecPlan of which reference concepts or code were adopted, changed or rejected.
