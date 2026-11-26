"""Casual Roman (Hinglish) spellings of Devanagari Hindi words, for a Roman -> Devanagari lookup."""
import itertools, re
CONS = {
 'क':['k'],'ख':['kh'],'ग':['g'],'घ':['gh'],'ङ':['n'],'च':['ch'],'छ':['chh','ch'],'ज':['j'],'झ':['jh'],'ञ':['n'],
 'ट':['t'],'ठ':['th'],'ड':['d'],'ढ':['dh'],'ण':['n'],'त':['t'],'थ':['th'],'द':['d'],'ध':['dh'],'न':['n'],
 'प':['p'],'फ':['ph','f'],'ब':['b'],'भ':['bh'],'म':['m'],'य':['y'],'र':['r'],'ल':['l'],'व':['v','w'],
 'श':['sh'],'ष':['sh'],'स':['s'],'ह':['h'],'ळ':['l'],
}
NUKTA = {'क':['q','k'],'ख':['kh'],'ग':['g'],'ज':['z','j'],'फ':['f','ph'],'ड':['d','r'],'ढ':['dh','rh']}
PRE = {'क़':'क','ख़':'ख','ग़':'ग','ज़':'ज','फ़':'फ','ड़':'ड','ढ़':'ढ'}
VOW = {'अ':['a'],'आ':['aa','a'],'इ':['i'],'ई':['ee','i'],'उ':['u'],'ऊ':['oo','u'],'ए':['e'],'ऐ':['ai','ae','e'],'ओ':['o'],'औ':['au','ao'],'ऋ':['ri'],'ऑ':['o'],'ऍ':['e']}
MAT = {'ा':['aa','a'],'ि':['i'],'ी':['ee','i'],'ु':['u'],'ू':['oo','u'],'ृ':['ri'],'े':['e'],'ै':['ai','e','ae'],'ो':['o'],'ौ':['au','ao'],'ॉ':['o'],'ॅ':['e']}
VIRAMA='्'; ANU='ं'; CHANDRA='ँ'; NUK='़'; VIS='ः'

def units(w):
    for k,v in PRE.items(): w=w.replace(k, v+NUK)
    out=[]; i=0
    while i<len(w):
        c=w[i]
        if c in CONS:
            nuk = i+1<len(w) and w[i+1]==NUK
            j=i+2 if nuk else i+1
            opts = NUKTA.get(c, CONS[c]) if nuk else CONS[c]
            vowel=None; halant=False
            if j<len(w) and w[j]==VIRAMA: halant=True; j+=1
            elif j<len(w) and w[j] in MAT: vowel=MAT[w[j]]; j+=1
            out.append(('C', opts, vowel, halant)); i=j
        elif c in VOW: out.append(('V', VOW[c])); i+=1
        elif c in (ANU, CHANDRA): out.append(('N', c)); i+=1
        elif c==VIS: out.append(('H',)); i+=1
        else: return None
    return out

def variants(w, cap=16):
    u=units(w)
    if not u: return []
    n=len(u)
    # Which consonants keep their inherent 'a' (schwa): not the last one; in the middle, the
    # common rule drops it in V C a C V (karna, samajhna); give both readings in the middle.
    pieces=[]
    for idx,x in enumerate(u):
        if x[0]=='C':
            _,opts,vowel,halant=x
            if halant: vs=['']
            elif vowel: vs=vowel
            else:
                last = idx==n-1 or all(y[0] in 'NH' for y in u[idx+1:])
                if last: vs=['']
                else:
                    nxt=u[idx+1]
                    # medial schwa: often dropped in speech and spelling (samajhna/samjhna, karna)
                    if idx>0 and nxt[0]=='C': vs=['a','']
                    else: vs=['a']
            # a doubled consonant (च्छ, क्क, त्त): casual spellings write it once or twice
            pieces.append([o+v for o in opts for v in vs])
        elif x[0]=='V': pieces.append(x[1])
        elif x[0]=='N':
            final = idx==n-1
            labial = idx+1<n and u[idx+1][0]=='C' and u[idx+1][1][0][0] in 'pbm'
            pieces.append(['n',''] if final else (['m','n'] if labial else ['n']))
        else: pieces.append(['h',''])
    out=[]
    for combo in itertools.product(*pieces):
        s=''.join(combo)
        out.append(s)
        if len(out)>400: break
    extra=[]
    for s in out:
        for a,b in (('chchh','chh'),('chchh','cch'),('chch','chh'),('chch','cch'),('chch','ch'),('chchh','ch')):
            if a in s: extra.append(s.replace(a,b))
        extra.append(re.sub(r'([bcdfgjklmnpqrstvwxyz])\1', r'\1', s))
    seen=[]; 
    for s in out+extra:
        if s and s not in seen: seen.append(s)
    # prefer short, common-looking spellings first
    seen.sort(key=lambda s:(len(s), s))
    return seen[:cap]

if __name__=='__main__':
    for w in ['बहुत','अच्छा','है','हैं','मैं','में','हूँ','करना','समझना','क्या','नहीं','ज़िंदगी','प्यार','आपके','सकती','गाना','दोस्त','ख़ुश','कहाँ','चलो']:
        print(w, variants(w))
