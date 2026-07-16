.PHONY: doctor bootstrap validate test lint assemble verify codex codex-master clean

doctor:
	./scripts/doctor.sh

bootstrap:
	./scripts/bootstrap.sh

validate:
	./scripts/validate_project.sh

test:
	./gradlew :app:testDebugUnitTest

lint:
	./gradlew :app:lintDebug

assemble:
	./gradlew :app:assembleDebug

verify:
	./scripts/verify.sh

codex:
	./scripts/codex-start.sh

codex-master:
	./scripts/codex-run-master.sh

clean:
	./gradlew clean
