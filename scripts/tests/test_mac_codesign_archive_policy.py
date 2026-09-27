"""Public Mach-O signing certificates never exempt private-key content."""
import struct
import shutil
import unittest
from scripts.check_public_surface import mach_o_certificate_ranges, der_signing_material_outside_ranges


def cms():
    def der(tag, value):
        return bytes((tag, len(value))) + value
    algorithm = der(0x30, bytes.fromhex('0609608648016503040201'))
    signer = der(0x30, der(2, b'\x01') + der(0x30, der(0x30, b'') + der(2, b'\x01'))
                 + algorithm + algorithm + der(4, b'x'))
    signed = der(0x30, der(2, b'\x01') + der(0x31, algorithm)
                 + der(0x30, bytes.fromhex('06092a864886f70d010701')) + der(0x31, signer))
    return der(0x30, bytes.fromhex('06092a864886f70d010702') + der(0xA0, signed))


def macho(payload):
    wrapper = struct.pack('>II', 0xFADE0B01, len(payload) + 8) + payload
    signature = struct.pack('>IIIII', 0xFADE0CC0, len(wrapper) + 20, 1, 0x10000, 20) + wrapper
    header = struct.pack('<IIIIIIII', 0xFEEDFACF, 0, 0, 6, 1, 16, 0, 0)
    return header + struct.pack('<IIII', 0x1D, 16, 48, len(signature)) + signature


class MacCodeSignArchivePolicyTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('openssl'), 'OpenSSL is needed for Apple BER CMS')
    def test_apple_ber_wrapper_is_canonicalized_but_malformed_data_fails(self):
        der = cms()
        self.assertLess(der[1], 128)
        ber = b'\x30\x80' + der[2:] + b'\x00\x00'
        self.assertIsNotNone(mach_o_certificate_ranges(macho(ber)))
        self.assertIsNone(mach_o_certificate_ranges(macho(ber[:-1])))

    def test_only_complete_cms_range_is_recognized(self):
        payload = cms()
        value = macho(payload)
        ranges = mach_o_certificate_ranges(value)
        self.assertEqual(((76, 76 + len(payload)),), ranges)
        self.assertIsNone(der_signing_material_outside_ranges(value, ranges))
        self.assertIsNone(mach_o_certificate_ranges(macho(b'not CMS')))
        self.assertIsNone(mach_o_certificate_ranges(value[:-1]))
        broken = bytearray(value)
        struct.pack_into('<I', broken, 40, 0xFFFFFFFF)
        self.assertIsNone(mach_o_certificate_ranges(bytes(broken)))

    def test_private_key_still_fails_inside_or_outside_certificate_ranges(self):
        private = bytes.fromhex('3015020100300d06092a864886f70d0101010500040178')
        value = macho(cms())
        self.assertIsNotNone(der_signing_material_outside_ranges(value + private, mach_o_certificate_ranges(value)))
        # Even an excluded range is scanned globally for private-key structures.
        self.assertIsNotNone(der_signing_material_outside_ranges(private, ((0, len(private)),)))
        self.assertIsNone(mach_o_certificate_ranges(macho(private)))
