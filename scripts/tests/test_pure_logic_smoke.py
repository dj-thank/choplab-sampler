import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class PureLogicSmokeSourceTest(unittest.TestCase):
    def test_pattern_renderer_smoke_includes_shared_dsp_primitives(self) -> None:
        script = (ROOT / "scripts" / "run_pure_logic_smoke.sh").read_text(encoding="utf-8")

        dsp = "shared/src/commonMain/kotlin/com/choplab/sampler/audio/SamplerDspPrimitives.kt"
        renderer = "jvm-core/src/main/kotlin/com/choplab/sampler/audio/PatternRenderer.kt"
        self.assertIn(dsp, script)
        self.assertIn(renderer, script)
        self.assertLess(script.index(dsp), script.index(renderer))


if __name__ == "__main__":
    unittest.main()
