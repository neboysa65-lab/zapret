import sys
import struct
import os
import glob
import zipfile

# Only 64-bit ABIs matter for the Play 16 KB page-size requirement.
# Play validates ELF PT_LOAD alignment of native libs it ships.
TARGET_ABIS = ('arm64-v8a', 'x86_64')
PAGE_SIZE = 16384

SOURCE_ROOTS = (
    'amneziawg-tunnel/src/main/jniLibs',
    'vyom-tun-sdk/src/main/jniLibs',
)


def check_alignment(filepath, from_zip=False):
    """Return list of PT_LOAD p_align values, or an error string."""
    if from_zip:
        f = zipfile.ZipFile(filepath)
        blob = f.read(ARCHIVE_ENTRY)
    else:
        with open(filepath, 'rb') as f:
            blob = f.read()

    if blob[:4] != b'\x7fELF':
        return "Not an ELF"
    file_class = blob[4]
    if file_class != 2:
        return "Not 64-bit ELF"

    endian = blob[5]
    fmt = '<' if endian == 1 else '>'

    if file_class == 2:
        e_phoff = struct.unpack_from(fmt + 'Q', blob, 32)[0]
        e_phentsize, e_phnum = struct.unpack_from(fmt + 'HH', blob, 54)
    else:
        e_phoff = struct.unpack_from(fmt + 'I', blob, 28)[0]
        e_phentsize, e_phnum = struct.unpack_from(fmt + 'HH', blob, 42)

    aligns = []
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        if file_class == 2:
            p_type, _, p_offset, p_vaddr, _, _, _, p_align = struct.unpack_from(fmt + 'IIQQQQQQ', blob, o)
        else:
            p_type, p_offset, p_vaddr, _, _, _, _, p_align = struct.unpack_from(fmt + 'IIIIIIII', blob, o)
        if p_type == 1:  # PT_LOAD
            aligns.append((p_align, p_offset, p_vaddr))

    return aligns


def is_16k(segments):
    """16 KB compatible if every PT_LOAD is >=16 KB aligned AND
    p_offset is congruent to p_vaddr modulo p_align (loader requirement)."""
    for align, offset, vaddr in segments:
        if align <= 1:
            continue
        if align < PAGE_SIZE:
            return False
        if offset % align != vaddr % align:
            return False
    return True


def main():
    aab = sys.argv[1] if len(sys.argv) > 1 else None
    global ARCHIVE_ENTRY

    if aab:
        print(f"Checking AAB: {aab}\n")
        z = zipfile.ZipFile(aab)
        entries = [n for n in z.namelist()
                   if n.startswith('base/lib/') and n.endswith('.so')
                   and n.split('/')[2] in TARGET_ABIS]
        all_ok = True
        for entry in sorted(entries):
            abi = entry.split('/')[2]
            ARCHIVE_ENTRY = entry
            segs = check_alignment(aab, from_zip=True)
            if isinstance(segs, str):
                print(f"  {abi}/{os.path.basename(entry)}: {segs}")
                continue
            ok = is_16k(segs)
            all_ok &= ok
            aligns = sorted({hex(s[0]) for s in segs})
            print(f"  [{'OK  ' if ok else 'FAIL'}] {abi:10s} {os.path.basename(entry):34s} PT_LOAD aligns={','.join(aligns)}")
        print(f"\nOverall 16KB Compatibility: {'YES' if all_ok else 'NO'}")
        return 0 if all_ok else 1

    files = []
    for root in SOURCE_ROOTS:
        for abi in TARGET_ABIS:
            files += glob.glob(os.path.join(root, abi, '*.so'))

    if not files:
        print("No native libraries found in source jniLibs. Pass an .aab path to check a build.")
        return 1

    print(f"Total 64-bit .so files found: {len(files)}\n")
    all_ok = True
    for f in sorted(files):
        segs = check_alignment(f)
        if isinstance(segs, str):
            print(f"  {os.path.basename(f)}: {segs}")
            continue
        ok = is_16k(segs)
        all_ok &= ok
        aligns = sorted({hex(s[0]) for s in segs})
        status = '16KB Compatible' if ok else 'Needs 16KB Alignment'
        print(f"  [{'OK  ' if ok else 'FAIL'}] {f}")
        print(f"         PT_LOAD aligns={','.join(aligns)} - {status}")

    print(f"\nOverall 16KB Compatibility: {'YES' if all_ok else 'NO'}")
    return 0 if all_ok else 1


if __name__ == '__main__':
    sys.exit(main())