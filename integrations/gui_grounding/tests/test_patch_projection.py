"""CPU comparisons against the original Conv3d patch projection."""
import importlib
import importlib.util
from pathlib import Path
import sys
import unittest

import torch
from torch import nn

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class PatchProjectionTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(importlib.util.find_spec("patch_projection"),
                             "The linear patch projection has not been implemented")
        self.projector = importlib.import_module("patch_projection").LinearPatchProjection
        self.generator = torch.Generator(device="cpu").manual_seed(731)

    def convolution(self, *, dtype=torch.float32, bias=True, **updates):
        arguments = dict(in_channels=3, out_channels=8, kernel_size=(2, 16, 16),
                         stride=(2, 16, 16), padding=0, dilation=1, groups=1,
                         bias=bias, device="cpu", dtype=dtype)
        arguments.update(updates)
        layer = nn.Conv3d(**arguments)
        with torch.no_grad():
            layer.weight.copy_(torch.randn(layer.weight.shape, dtype=dtype,
                                           generator=self.generator) / 32)
            if layer.bias is not None:
                layer.bias.copy_(torch.randn(layer.bias.shape, dtype=dtype,
                                             generator=self.generator))
        return layer

    def test_float32_and_float64_match_original_convolution(self):
        for dtype in (torch.float32, torch.float64):
            for bias in (False, True):
                with self.subTest(dtype=dtype, bias=bias):
                    convolution = self.convolution(dtype=dtype, bias=bias)
                    replacement = self.projector(convolution)
                    value = torch.randn((5, 3, 2, 16, 16), dtype=dtype,
                                        generator=self.generator, device="cpu")
                    expected = convolution(value)
                    actual = replacement(value)
                    self.assertEqual((5, 8, 1, 1, 1), tuple(actual.shape))
                    tolerance = 1e-5 if dtype == torch.float32 else 1e-12
                    torch.testing.assert_close(actual, expected, rtol=tolerance, atol=tolerance)

    def test_parameters_are_same_objects_and_original_updates_are_visible(self):
        convolution = self.convolution()
        convolution.weight.requires_grad_(False)
        replacement = self.projector(convolution)
        self.assertIs(replacement.weight, convolution.weight)
        self.assertIs(replacement.bias, convolution.bias)
        self.assertFalse(replacement.weight.requires_grad)
        self.assertEqual(convolution.weight.data_ptr(), replacement.weight.data_ptr())
        self.assertEqual(convolution.bias.data_ptr(), replacement.bias.data_ptr())
        value = torch.ones((1, 3, 2, 16, 16), device="cpu")
        with torch.no_grad():
            convolution.weight.zero_()
            convolution.bias.fill_(2)
        torch.testing.assert_close(replacement(value), torch.full((1, 8, 1, 1, 1), 2.0))

    def test_biasless_projection_keeps_absent_parameter(self):
        convolution = self.convolution(bias=False)
        replacement = self.projector(convolution)
        self.assertIs(replacement.weight, convolution.weight)
        self.assertIsNone(replacement.bias)
        self.assertEqual({"weight"}, set(replacement.state_dict()))

    def test_noncontiguous_input_retains_convolution_element_order(self):
        convolution = self.convolution(dtype=torch.float64)
        value = torch.randn((2, 3, 2, 16, 16), dtype=torch.float64,
                            generator=self.generator, device="cpu").transpose(-1, -2)
        self.assertFalse(value.is_contiguous())
        torch.testing.assert_close(self.projector(convolution)(value), convolution(value),
                                   rtol=1e-12, atol=1e-12)

    def test_input_and_parameter_gradients_match_original_convolution(self):
        convolution = self.convolution(dtype=torch.float64)
        replacement = self.projector(convolution)
        value = torch.randn((2, 3, 2, 16, 16), dtype=torch.float64,
                            generator=self.generator, device="cpu", requires_grad=True)
        parameters = (value, convolution.weight, convolution.bias)
        expected = torch.autograd.grad(convolution(value).square().sum(), parameters)
        actual = torch.autograd.grad(replacement(value).square().sum(), parameters)
        for result, reference in zip(actual, expected):
            torch.testing.assert_close(result, reference, rtol=1e-11, atol=1e-11)

    def test_rejects_convolutions_that_are_not_single_qwen_patches(self):
        unsupported = [dict(kernel_size=(1, 16, 16)), dict(stride=(1, 16, 16)),
                       dict(padding=1), dict(dilation=(2, 1, 1)),
                       dict(groups=3, out_channels=9), dict(in_channels=4),
                       dict(padding_mode="reflect"), dict(padding="same", stride=1)]
        for updates in unsupported:
            with self.subTest(updates=updates), self.assertRaises(ValueError):
                self.projector(self.convolution(**updates))
        with self.assertRaises(TypeError):
            self.projector(nn.Linear(1536, 8))

    def test_rejects_inputs_that_change_patch_geometry(self):
        replacement = self.projector(self.convolution())
        for shape in [(2, 1536), (2, 3, 2, 16, 32), (2, 3, 1, 16, 16),
                      (2, 1, 2, 16, 16), (3, 2, 16, 16)]:
            with self.subTest(shape=shape), self.assertRaises(ValueError):
                replacement(torch.zeros(shape, device="cpu"))


if __name__ == "__main__":
    unittest.main()
