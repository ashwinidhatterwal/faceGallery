import struct,unittest
from verify_release import verify_native

def library(alignment=16384,relro_end=16384):
    data=bytearray(64+2*56);data[:6]=b'\x7fELF\x02\x01'
    struct.pack_into('<Q',data,32,64);struct.pack_into('<HH',data,54,56,2)
    struct.pack_into('<IIQQQQQQ',data,64,1,6,0,0,0,100,32768,alignment)
    struct.pack_into('<IIQQQQQQ',data,120,0x6474e552,4,0,0,0,100,relro_end,1)
    return data

class ReleaseVerificationTest(unittest.TestCase):
    def test_valid_16kb_native(self):verify_native(library())
    def test_4kb_dependency_rejected(self):
        with self.assertRaises(ValueError):verify_native(library(4096))
    def test_relro_misalignment_rejected(self):
        with self.assertRaises(ValueError):verify_native(library(relro_end=4096))
    def test_truncated_native_rejected(self):
        with self.assertRaises(ValueError):verify_native(library()[:70])
