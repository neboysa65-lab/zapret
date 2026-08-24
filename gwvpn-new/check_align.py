import os
import struct
import sys

def get_elf_align(filepath):
    try:
        with open(filepath, 'rb') as f:
            e_ident = f.read(16)
            if not e_ident.startswith(b'\x7fELF'):
                return "Not ELF"

            is_64_bit = (e_ident[4] == 2)
            endian = '<' if e_ident[5] == 1 else '>'

            if is_64_bit:
                # e_type(2) e_machine(2) e_version(4) e_entry(8) e_phoff(8) e_shoff(8) e_flags(4) e_ehsize(2) e_phentsize(2) e_phnum(2)
                # 2 + 2 + 4 + 8 + 8 + 8 + 4 + 2 + 2 + 2 = 42
                fmt = endian + 'H H I Q Q Q I H H H'
                hdr = f.read(42)
                fields = struct.unpack(fmt, hdr)
                e_phoff = fields[4]
                e_phentsize = fields[8]
                e_phnum = fields[9]
            else:
                # e_type(2) e_machine(2) e_version(4) e_entry(4) e_phoff(4) e_shoff(4) e_flags(4) e_ehsize(2) e_phentsize(2) e_phnum(2)
                # 2 + 2 + 4 + 4 + 4 + 4 + 4 + 2 + 2 + 2 = 30
                fmt = endian + 'H H I I I I I H H H'
                hdr = f.read(30)
                fields = struct.unpack(fmt, hdr)
                e_phoff = fields[4]
                e_phentsize = fields[8]
                e_phnum = fields[9]

            aligns = []
            for i in range(e_phnum):
                f.seek(e_phoff + i * e_phentsize)
                if is_64_bit:
                    # p_type(4), p_flags(4), p_offset(8), p_vaddr(8), p_paddr(8), p_filesz(8), p_memsz(8), p_align(8)
                    fmt_phdr = endian + 'I I Q Q Q Q Q Q'
                    phdr = f.read(56)
                    p_fields = struct.unpack(fmt_phdr, phdr)
                    p_type = p_fields[0]
                    p_align = p_fields[7]
                else:
                    # p_type(4), p_offset(4), p_vaddr(4), p_paddr(4), p_filesz(4), p_memsz(4), p_flags(4), p_align(4)
                    fmt_phdr = endian + 'I I I I I I I I'
                    phdr = f.read(32)
                    p_fields = struct.unpack(fmt_phdr, phdr)
                    p_type = p_fields[0]
                    p_align = p_fields[7]
                
                if p_type == 1: # PT_LOAD
                    aligns.append(p_align)

            if aligns:
                return min(aligns)
            return "No LOAD segments"
    except Exception as e:
        return f"Error: {e}"

so_files = []
for root, _, files in os.walk('.'):
    for f in files:
        if f.endswith('.so'):
            so_files.append(os.path.join(root, f))

for f in sorted(so_files):
    align = get_elf_align(f)
    if isinstance(align, int):
        if align >= 16384:
             print(f"[OK 16KB] Align={align:<5} {f}")
        else:
             print(f"[FAIL 4KB] Align={align:<5} {f}")
    else:
        print(f"[ERROR] {align} {f}")
