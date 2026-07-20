import importlib.util
import pathlib
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("paddleocr_sidecar.py")
SPEC = importlib.util.spec_from_file_location("paddleocr_sidecar", MODULE_PATH)
SIDECAR = importlib.util.module_from_spec(SPEC)
assert SPEC is not None and SPEC.loader is not None
SPEC.loader.exec_module(SIDECAR)


class FakeDevice:
    def __init__(self, available, custom_types=(), set_error=None):
        self.available = available
        self.custom_types = custom_types
        self.set_error = set_error
        self.selected = None

    def get_available_device(self):
        return self.available

    def get_all_custom_device_type(self):
        return self.custom_types

    def set_device(self, device):
        if self.set_error is not None:
            raise RuntimeError(self.set_error)
        self.selected = device


class FakePaddle:
    def __init__(self, cuda=False, xpu=False, available=(), custom_types=(), set_error=None):
        self.device = FakeDevice(available, custom_types, set_error)
        self._cuda = cuda
        self._xpu = xpu

    def is_compiled_with_cuda(self):
        return self._cuda

    def is_compiled_with_xpu(self):
        return self._xpu


class PaddleRuntimeTest(unittest.TestCase):
    def test_auto_selects_real_cuda_device(self):
        paddle = FakePaddle(cuda=True, xpu=True, available=["xpu:0", "gpu:0", "CustomGPU:0"], custom_types=["CustomGPU"])

        runtime = SIDECAR.probe_paddle_runtime("auto", paddle)

        self.assertEqual("gpu:0", runtime["device"])
        self.assertEqual("cuda", runtime["backend"])
        self.assertTrue(runtime["device_available"])
        self.assertEqual(["xpu:0", "gpu:0", "CustomGPU:0"], paddle.device.available)
        self.assertEqual("gpu:0", paddle.device.selected)

    def test_auto_uses_cpu_when_paddle_has_no_accelerator(self):
        runtime = SIDECAR.probe_paddle_runtime("auto", FakePaddle())

        self.assertEqual("cpu", runtime["device"])
        self.assertEqual("cpu", runtime["backend"])
        self.assertTrue(runtime["device_available"])
        self.assertIsNone(runtime["error"])

    def test_explicit_gpu_does_not_silently_fallback_to_cpu(self):
        runtime = SIDECAR.probe_paddle_runtime("gpu", FakePaddle())

        self.assertEqual("gpu:0", runtime["device"])
        self.assertEqual("cuda", runtime["backend"])
        self.assertFalse(runtime["device_available"])
        self.assertIn("unavailable", runtime["error"])

    def test_auto_can_select_custom_accelerator(self):
        paddle = FakePaddle(available=["CustomGPU:0"], custom_types=["CustomGPU"])

        runtime = SIDECAR.probe_paddle_runtime("auto", paddle)

        self.assertEqual("CustomGPU:0", runtime["device"])
        self.assertEqual("custom:customgpu", runtime["backend"])
        self.assertTrue(runtime["device_available"])


if __name__ == "__main__":
    unittest.main()
