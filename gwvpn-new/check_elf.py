import sys
import struct
import os
import glob

def check_alignment(filepath):
    with open(filepath, 'rb') as f:
        magic = f.read(4)
        if magic != b'\x7fELF':
            return "Not an ELF"
        file_class = f.read(1)[0]
        if file_class != 2:
            return "Not 64-bit ELF"
        
        # Read endianness
        endian = f.read(1)[0]
        fmt = '<' if endian == 1 else '>'
        
        f.seek(32) # e_phoff
        e_phoff = struct.unpack(fmt + 'Q', f.read(8))[0]
        
        f.seek(54) # e_phentsize, e_phnum
        e_phentsize = struct.unpack(fmt + 'H', f.read(2))[0]
        e_phnum = struct.unpack(fmt + 'H', f.read(2))[0]
        
        aligns = []
        for i in range(e_phnum):
            f.seek(e_phoff + i * e_phentsize)
            # p_type(4), p_flags(4), p_offset(8), p_vaddr(8), p_paddr(8), p_filesz(8), p_memsz(8), p_align(8)
            phdr = f.read(56)
            if len(phdr) < 56: continue
            p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack(fmt + 'IIQQQQQQ', phdr)
            if p_type == 1: # PT_LOAD
                aligns.append(p_align)
        
        return aligns

so_files = glob.glob('tmp_apk_extract/lib/arm64-v8a/*.so') + glob.glob('tmp_apk_extract/lib/x86_64/*.so')
print(f"Total .so files found: {len(so_files)}")
all_16k_compatible = True
for f in so_files:
    aligns = check_alignment(f)
    if isinstance(aligns, str):
        print(f"{os.path.basename(f)}: {aligns}")
        continue
    
    is_16k = True
    for a in aligns:
        if a % 16384 != 0 and a != 4096: # Usually it's max-page-size, so align should be >= 16384 or exactly 0/1, but typical PT_LOAD aligns are 4096 or 16384 or 65536
            pass
        if a < 16384 and a > 1: # If alignment is 4096, it won't be compatible with 16k pages (usually)
            is_16k = False
            
    print(f"{os.path.basename(f)}: PT_LOAD aligns = {[hex(a) for a in aligns]} - {'16KB Compatible' if is_16k else 'Needs 16KB Alignment (current is 4KB)'}")
    if not is_16k: all_16k_compatible = False

print(f"\nOverall 16KB Compatibility: {'YES' if all_16k_compatible else 'NO'}")
