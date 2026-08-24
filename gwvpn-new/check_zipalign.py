import zipfile
import struct
import sys

def check_apk_alignment(apk_path):
    with open(apk_path, 'rb') as f:
        # We need to find the local file header offset for .so files
        zf = zipfile.ZipFile(f)
        unaligned = []
        for info in zf.infolist():
            if info.filename.endswith('.so') and ('arm64-v8a' in info.filename or 'x86_64' in info.filename):
                f.seek(info.header_offset)
                magic = f.read(4)
                if magic != b'PK\x03\x04': continue
                f.read(22)
                fname_len, extra_len = struct.unpack('<HH', f.read(4))
                data_offset = info.header_offset + 30 + fname_len + extra_len
                
                # Check if uncompressed
                if info.compress_type != 0:
                    print(f"{info.filename}: Compressed (extractNativeLibs=true needed or it will crash if false)")
                    continue
                
                if data_offset % 16384 != 0:
                    unaligned.append((info.filename, data_offset))
                    
        if unaligned:
            print(f"Found {len(unaligned)} unaligned 64-bit .so files (not 16KB aligned):")
            for name, offset in unaligned[:5]:
                print(f"  {name} (offset: {offset}, remainder: {offset % 16384})")
            return False
        else:
            print("All 64-bit .so files in APK are 16KB aligned!")
            return True

check_apk_alignment("app/release/GreyWebVPN-3.0.4 [272]-release.apk")
