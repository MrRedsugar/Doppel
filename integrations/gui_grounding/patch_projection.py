"""Linear equivalent of Qwen3-VL's isolated, non-overlapping patch Conv3d.

Only a single 3 x 2 x 16 x 16 patch per batch item is supported. Each output
therefore has one spatial position, and Conv3d's cross-correlation is exactly
the same dot product as a linear projection of the patch in C/T/H/W order.
This module does not move, cast, or clone the original parameters.
"""
from torch import nn
from torch.nn import functional as F


class LinearPatchProjection(nn.Module):
    """Reuse a compatible Conv3d's parameters while avoiding its kernel call."""

    def __init__(self, projection: nn.Conv3d):
        super().__init__()
        if not isinstance(projection, nn.Conv3d):
            raise TypeError("Expected a Conv3d patch projection")
        if (projection.in_channels != 3
                or projection.kernel_size != (2, 16, 16)
                or projection.stride != (2, 16, 16)
                or projection.padding != (0, 0, 0)
                or projection.dilation != (1, 1, 1)
                or projection.groups != 1
                or projection.padding_mode != "zeros"):
            raise ValueError("Only unpadded, ungrouped 3 x 2 x 16 x 16 single-patch Conv3d is supported")
        self.in_channels = projection.in_channels
        self.out_channels = projection.out_channels
        self.kernel_size = projection.kernel_size
        self.stride = projection.stride
        self.padding = projection.padding
        self.dilation = projection.dilation
        self.groups = projection.groups
        self.padding_mode = projection.padding_mode
        self.register_parameter("weight", projection.weight)
        self.register_parameter("bias", projection.bias)
        self.train(projection.training)

    def forward(self, value):
        if value.ndim != 5 or tuple(value.shape[1:]) != (3, 2, 16, 16):
            raise ValueError("Expected one patch per item with shape N x 3 x 2 x 16 x 16")
        result = F.linear(value.flatten(1), self.weight.flatten(1), self.bias)
        return result.reshape(value.shape[0], self.out_channels, 1, 1, 1)
