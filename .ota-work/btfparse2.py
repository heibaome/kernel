import struct, sys

def parse(btf):
    hdr_len=24
    toff,tlen,soff,slen=struct.unpack('<IIII',btf[8:24])
    types=btf[hdr_len+toff:hdr_len+toff+tlen]
    strs=btf[hdr_len+soff:hdr_len+soff+slen]
    def nm(o):
        if o==0: return '(anon)'
        e=strs.find(b'\x00',o); return strs[o:e].decode('utf8','replace')
    idx={}; members={}; pos=0; tid=1; swapped=False
    n=len(types)
    while pos+12<=n:
        name_off,info=struct.unpack('<II',types[pos:pos+8])
        kind=(info>>24)&0x1F; vlen=info&0xFFFF
        if kind==13: kind=12; swapped=True   # 实证：该内核 BTF 的 12/13 互换
        elif kind==12: kind=13
        if kind in (4,5):
            sz=struct.unpack('<I',types[pos+8:pos+12])[0]
            ms=[]
            for k in range(vlen):
                mn,mt,mo=struct.unpack('<III',types[pos+12+k*12:pos+24+k*12])
                ms.append((nm(mn),mt,mo))
            members[tid]=ms; idx[tid]=(kind,nm(name_off),sz)
            pos+=12+vlen*12
        elif kind==13:  # FUNC
            idx[tid]=(kind,nm(name_off),None); pos+=12
        else:
            extra={1:4,3:12,6:vlen*8,7:0,14:4,17:4,18:4,19:vlen*12}.get(kind,0)
            # kind 12 FUNC_PROTO: 12+vlen*8
            if kind==12: extra=vlen*8
            if pos+12+extra>n: break
            idx[tid]=(kind,nm(name_off),None)
            pos+=12+extra
        tid+=1
    return idx, members, swapped, tid

def walk(members, tid, path):
    off=0
    for want in path:
        ms=members.get(tid)
        if ms is None: return None
        for mn,mt,mo in ms:
            if mn==want: off+=mo; tid=mt; break
        else: return None
    return off,tid

if __name__=='__main__':
    btf=open(sys.argv[1],'rb').read()
    idx,members,swapped,ntid=parse(btf)
    print('types:',ntid-1,'swapped12/13:',swapped)
    byname={}
    for tid,(kind,tname,sz) in idx.items():
        if kind==4 and tname not in byname: byname[tname]=(tid,sz)
    for w in ('task_struct','cred','k_itimer'):
        print(w,'->',byname.get(w))
    ts=byname.get('task_struct')
    if ts:
        r=walk(members,ts[0],['cred'])
        print('task_struct.cred byte offset:',r[0]//8 if r else None)
    cred=byname.get('cred')
    if cred:
        print('cred size:',cred[1],'cred.uid byte:',[mo//8 for mn,mt,mo in members[cred[0]] if mn=='uid'])
    kit=byname.get('k_itimer')
    if kit:
        r=walk(members,kit[0],['it','real_timer','node'])
        print('k_itimer.it.real_timer.node byte:',r[0]//8 if r else None)
        if r is None:
            for mn,mt,mo in members[kit[0]]:
                if mn=='it':
                    print(' union it:',[(x[0],x[2]//8) for x in members.get(mt,[])])
