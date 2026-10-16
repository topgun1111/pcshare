import random, sys
KEEP=4; TOPN=15; NC=7

class N:
    def __init__(s,name,dir,parent):
        s.name=name;s.dir=dir;s.parent=parent;s.size=0;s.files=0;s.folders=0;s.cat=0;s.agg=False;s.denied=False;s.cats=None;s.kids=[]
    def path(s):
        if s.parent is None: return "/"
        parts=[];n=s
        while n is not None and n.parent is not None:
            if not (n.agg and n is s): parts.append(n.name)
            n=n.parent
        return "/"+"/".join(reversed(parts)) if parts else "/"

# ground truth fs: dict name -> dict(folder) | (size,cat); folder = {'_denied':bool,'_c':{...}}
def gen(depth,rng):
    f={'_denied':False,'_c':{}}
    if depth>0 and rng.random()<0.08: f['_denied']=True; return f
    for i in range(rng.choice([0,1,2,3,6,9])):
        f['_c']['f%d'%i]=(rng.choice([0,0,1,5,10,50,100,1000,7,7]),rng.randrange(NC))
    if depth<4:
        for i in range(rng.choice([0,1,2,3])):
            f['_c']['d%d'%i]=gen(depth+1,rng)
    return f

def scan(fs,node,top):
    own=[(n,v) for n,v in fs['_c'].items() if isinstance(v,tuple)]
    subs=[(n,v) for n,v in fs['_c'].items() if isinstance(v,dict)]
    ob=sum(v[0] for _,v in own); of=len(own)
    cats=[0]*NC
    for _,v in own: cats[v[1]]+=v[0]
    cand=sorted([(n,v) for n,v in own if v[0]>0],key=lambda t:-t[1][0])[:KEEP]
    kept=[]
    for n,v in cand:
        k=N(n,False,node);k.size=v[0];k.files=1;k.cat=v[1];kept.append(k)
    for n,v in own:
        if v[0]>0:
            t=N(n,False,node);t.size=v[0];t.files=1;t.cat=v[1];top.append(t)
    node.size=ob;node.files=of
    if of>0: node.cats=cats
    kids=list(kept)
    rest=of-len(kept)
    if rest>0:
        a=N("",False,node);a.agg=True;a.size=ob-sum(k.size for k in kept);a.files=rest;kids.append(a)
    for n,v in subs:
        d=N(n,True,node)
        if v['_denied']: d.denied=True
        else: scan(v,d,top)
        kids.append(d)
    node.kids=kids

def fin(n):
    size=n.size;files=n.files;folders=0;cats=n.cats;drop=False
    for k in n.kids:
        if not k.dir: continue
        fin(k)
        size+=k.size;files+=k.files;folders+=1+k.folders
        if k.cats:
            if cats is None: cats=[0]*NC
            cats=list(cats) if cats is n.cats else cats
            for c in range(NC): cats[c]+=k.cats[c]
        if k.size==0 and k.files==0 and not k.denied: drop=True
    n.size=size;n.files=files;n.folders=folders;n.cats=cats
    kids=n.kids
    if drop: kids=[k for k in kids if not(k.dir and k.size==0 and k.files==0 and not k.denied)]
    kids=sorted(kids,key=lambda k:-k.size)
    n.kids=kids

def build(fs):
    root=N("",True,None);top=[]
    scan(fs,root,top);fin(root)
    top=sorted(top,key=lambda t:-t.size)[:TOPN]
    return root,top

# ---- Kotlin mirror (removeFile / removeNode / dropEmptyAndResort / pruneNested)
def drop_resort(p):
    near=p
    while True:
        up=near.parent
        if up is None: break
        if near.size!=0 or near.files!=0 or near.denied: break
        dead=near
        up.kids=[k for k in up.kids if k is not dead]
        near=up
    b=p
    while b is not None:
        if len(b.kids)>1: b.kids=sorted(b.kids,key=lambda k:-k.size)
        b=b.parent
    return near

class S: pass
def remove_file(s,n):
    if s.root is None or n.dir or n.agg: return None
    p=n.parent
    if p is None: return None
    size=n.size;cat=n.cat
    kept=next((k for k in p.kids if k is n),None) or next((k for k in p.kids if not k.dir and not k.agg and k.name==n.name),None)
    if kept is not None:
        p.kids=[k for k in p.kids if k is not kept]
    else:
        agg=next((k for k in p.kids if k.agg),None)
        if agg is None: return None
        agg.size=max(0,agg.size-size);agg.files-=1
        if agg.files<=0: p.kids=[k for k in p.kids if k is not agg]
    a=p
    while a is not None:
        a.size=max(0,a.size-size);a.files=max(0,a.files-1)
        if a.cats is not None: a.cats[cat]=max(0,a.cats[cat]-size)
        a=a.parent
    near=drop_resort(p)
    if s.top: s.top=[t for t in s.top if not(t.parent is p and t.name==n.name)]
    s.files-=1;s.bytes-=size
    s.removed+=size   # part 9b: Scan.removed
    return near

def remove_node(s,n):
    if s.root is None or not n.dir or n.agg: return None
    p=n.parent
    if p is None: return None
    if not any(k is n for k in p.kids): return None
    size=n.size;files=n.files;folders=1+n.folders;cats=n.cats
    p.kids=[k for k in p.kids if k is not n]
    a=p
    while a is not None:
        a.size=max(0,a.size-size);a.files=max(0,a.files-files);a.folders=max(0,a.folders-folders)
        if a.cats is not None and cats is not None:
            for c in range(NC): a.cats[c]=max(0,a.cats[c]-cats[c])
        a=a.parent
    near=drop_resort(p)
    def under(t):
        x=t.parent
        while x is not None and x is not n: x=x.parent
        return x is not None
    if s.top: s.top=[t for t in s.top if not under(t)]
    s.files-=files;s.bytes-=size
    s.removed+=size   # part 9b: Scan.removed
    return near

def prune(lst):
    st={id(n) for n in lst if not n.agg and n.parent is not None}
    done=set();out=[];twins=set()
    for n in lst:
        if id(n) not in st or id(n) in done: continue
        done.add(id(n))
        if not n.dir:
            key=(id(n.parent),n.name)
            if key in twins: continue
            twins.add(key)
        x=n.parent;nested=False
        while x is not None:
            if id(x) in st: nested=True;break
            x=x.parent
        if not nested: out.append(n)
    return out

def denied_below(n):
    c=1 if n.dir and n.denied else 0
    for k in n.kids:
        if k.dir: c+=denied_below(k)
    return c

def fs_get(fs,path):
    parts=[p for p in path.split('/') if p]
    for p in parts[:-1]: fs=fs['_c'][p]
    return fs,parts[-1]

def nodes_of(root):
    out=[];st=[root]
    while st:
        n=st.pop();out.append(n);st.extend(n.kids)
    return out

def tree_folder_paths(n,acc):
    if n.dir: acc[n.path()]=n
    for k in n.kids:
        if k.dir: tree_folder_paths(k,acc)

def truth_dirs(fs,path,acc):
    acc[path]=fs
    for n,v in fs['_c'].items():
        if isinstance(v,dict): truth_dirs(v,(path if path!="/" else "")+"/"+n,acc)

def check(root,top,fs,s,ctx):
    t,ttop=build(fs)
    # every folder node still in the tree matches the truth scan
    acc={};tree_folder_paths(root,acc)
    tacc={};tree_folder_paths(t,tacc)
    for p,n in acc.items():
        m=tacc.get(p)
        assert m is not None,(ctx,"folder not in truth",p)
        assert (n.size,n.files,n.folders,n.denied)==(m.size,m.files,m.folders,m.denied),(ctx,p,(n.size,n.files,n.folders),(m.size,m.files,m.folders))
        assert (n.cats or [0]*NC)==(m.cats or [0]*NC),(ctx,"cats",p,n.cats,m.cats)
        # kids sorted, sums
        ks=[k.size for k in n.kids]
        assert ks==sorted(ks,reverse=True),(ctx,"unsorted",p)
        if not n.denied:
            assert sum(k.size for k in n.kids)==n.size,(ctx,"sum size",p)
            assert sum(k.files for k in n.kids)==n.files,(ctx,"sum files",p)
    # nothing non-empty missing
    for p,m in tacc.items():
        if m.size>0 or m.files>0 or m.denied:
            assert p in acc,(ctx,"non-empty folder missing",p)
    # root totals / counters
    assert root.size==t.size and root.files==t.files,(ctx,"root")
    assert s.files==t.files and s.bytes==t.size,(ctx,"counters",s.files,t.files,s.bytes,t.size)
    # part 9b: "System / other" = used - (root.size + removed) must not move when something is deleted
    assert s.removed+root.size==s.total0,(ctx,"removed",s.removed,root.size,s.total0)
    # top: each entry exists in truth with same size; no duplicates
    seen=set()
    for e in s.top:
        key=(e.parent.path(),e.name)
        assert key not in seen,(ctx,"dup top",key)
        seen.add(key)
        d,nm=fs_get(fs,(e.parent.path() if e.parent.path()!="/" else "")+"/"+e.name)
        assert d['_c'].get(nm) is not None and d['_c'][nm][0]==e.size,(ctx,"stale top",key)
    # attachments: every kept node's parent chain is attached
    for n in nodes_of(root):
        for k in n.kids: assert k.parent is n

def run(seed):
    rng=random.Random(seed)
    fs=gen(0,rng);fs['_denied']=False
    root,top=build(fs)
    s=S();s.root=root;s.top=list(top);s.files=root.files;s.bytes=root.size;s.removed=0;s.total0=root.size
    ctx=[seed]
    for step in range(rng.randrange(1,6)):
        allnodes=[n for n in nodes_of(root) if n.parent is not None and not n.agg]
        pool=allnodes+list(s.top)
        if not pool: break
        # possibly an agg-file twin: a top copy whose kept node does not exist is already in pool
        picks=[rng.choice(pool) for _ in range(rng.randrange(1,5))]
        # sometimes force a folder + one of its descendants in the same batch
        if rng.random()<0.3:
            fo=[n for n in allnodes if n.dir and n.kids]
            if fo:
                f=rng.choice(fo);picks.append(f)
                desc=[x for x in nodes_of(f) if x is not f and not x.agg]
                if desc: picks.append(rng.choice(desc))
        picks.append(picks[0])   # duplicate
        batch=prune(picks)
        # prune property: no node inside another
        for a in batch:
            x=a.parent
            while x is not None:
                assert all(x is not b for b in batch),("nested kept",seed)
                x=x.parent
        # apply to truth + tree
        for n in batch:
            path=n.path() if not n.agg else None
            d,nm=fs_get(fs,path)
            if nm in d['_c']: del d['_c'][nm]
        for n in batch:
            r=remove_node(s,n) if n.dir else remove_file(s,n)
            assert r is not None,(seed,"remove returned None",n.path(),n.dir)
        ctx.append(step)
        check(root,s.top,fs,s,tuple(ctx))
        if root.size==0 and root.files==0: break
    return True

if __name__=="__main__":
    ok=0
    for sd in range(4000):
        run(sd);ok+=1
    print("passed",ok)
