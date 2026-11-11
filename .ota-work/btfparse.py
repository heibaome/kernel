import struct, sys

def parse(btf):
    magic, ver, flags, hdr_len = struct.unpack('<HBBI', btf[:8])
    type_off, type_len, str_off, str_len = struct.unpack('<IIII', btf[8:24])
    types = btf[hdr_len+type_off : hdr_len+type_off+type_len]
    strs  = btf[hdr_len+str_off : hdr_len+str_off+str_len]
    def name(off):
        if off == 0: return '(anon)'
        e = strs.find(b'\x00', off)
        return strs[off:e].decode('utf8','replace')
    idx = {}; members = {}
    pos = 0; tid = 1
    n = len(types)
    while pos + 12 <= n:
        name_off, info = struct.unpack('<II', types[pos:pos+8])
        kind = (info >> 24) & 0x1F
        vlen = info & 0xFFFF
        tname = name(name_off)
        if kind in (4,5):
            if pos+12+vlen*12 > n: break
            sz = struct.unpack('<I', types[pos+8:pos+12])[0]
            ms = []
            for k in range(vlen):
                mn, mt, mo = struct.unpack('<III', types[pos+12+k*12:pos+24+k*12])
                ms.append((name(mn), mt, mo))
            members[tid] = ms
            idx[tid] = (kind, tname, sz)
            pos += 12 + vlen*12
        else:
            extra = {1:4, 3:12, 6:vlen*8, 7:0, 12:vlen*8, 14:4, 15:vlen*12,
                     17:4, 19:vlen*12}.get(kind, 0)
            if pos+12+extra > n: break
            idx[tid] = (kind, tname, None)
            pos += 12 + extra
        tid += 1
    return name, members, idx

def walk(members, idx, tid, path):
    off = 0
    for want in path:
        ms = members.get(tid)
        if ms is None: return None
        for mn, mt, mo in ms:
            if mn == want:
                off += mo; tid = mt; break
        else:
            return None
    return off, tid

if __name__ == '__main__':
    btf = open(sys.argv[1],'rb').read()
    name, members, idx = parse(btf)
    print('types parsed:', len(idx))
    byname = {}
    for tid,(kind,tname,sz) in idx.items():
        if kind==4 and tname not in byname: byname[tname]=(tid,sz)
    for want in ('task_struct','cred','k_itimer','hrtimer'):
        print(want, '->', byname.get(want))
    ts = byname.get('task_struct')
    if ts:
        r = walk(members, idx, ts[0], ['cred'])
        print('task_struct.cred byte offset:', r[0]//8 if r else None)
    cred = byname.get('cred')
    if cred:
        print('cred size:', cred[1])
        print('cred.uid byte:', [mo//8 for mn,mt,mo in members[cred[0]] if mn=='uid'])
    kit = byname.get('k_itimer')
    if kit:
        r = walk(members, idx, kit[0], ['it','real_timer','node'])
        print('k_itimer.it.real_timer.node byte:', r[0]//8 if r else None)
        if r is None:
            print(' k_itimer members:', [(mn,mo//8) for mn,mt,mo in members[kit[0]]])
            for mn,mt,mo in members[kit[0]]:
                if mn=='it':
                    print(' union it:', [(x[0],x[2]//8) for x in members.get(mt,[])])
                    for x in members.get(mt,[]):
                        sub = members.get(x[1])
                        if sub and any(y[0]=='real_timer' for y in sub):
                            print('  rt struct:', [(y[0],y[2]//8) for y in sub])
                            for y in sub:
                                if y[0]=='real_timer':
                                    print('  hrtimer:', [(z[0],z[2]//8) for z in members.get(y[1],[])])
                                    for z in members.get(y[1],[]):
                                        if z[0]=='node':
                                            print('  node struct:', [(w[0],w[2]//8) for w in members.get(z[1],[])])
