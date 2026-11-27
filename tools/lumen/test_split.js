// Tests the exact placement function embedded in the shipped page, without a browser dependency.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const html = fs.readFileSync('app/src/main/assets/ui.html', 'utf8');
const start = html.indexOf('function lmSplitPlacement(');
const end = html.indexOf('\nlet splitOpen=', start);
assert(start >= 0 && end > start, 'placement function must exist');
const box = vm.runInNewContext(html.slice(start, end) + '\n;lmSplitPlacement');
const cases = [
  {name:'below', r:{top:50,bottom:98,right:290}, v:{left:0,top:0,width:360,height:720}, w:300,h:150, above:false},
  {name:'bottom sheet',r:{top:620,bottom:668,right:340},v:{left:0,top:0,width:360,height:720},w:300,h:190,above:true},
  {name:'folded narrow',r:{top:120,bottom:168,right:190},v:{left:0,top:0,width:210,height:450},w:300,h:500},
  {name:'wide',r:{top:40,bottom:88,right:900},v:{left:0,top:0,width:1200,height:800},w:300,h:160,above:false},
  {name:'offset viewport',r:{top:250,bottom:298,right:420},v:{left:100,top:100,width:340,height:420},w:300,h:150},
  {name:'long list',r:{top:220,bottom:268,right:320},v:{left:0,top:0,width:360,height:450},w:300,h:1800},
];
for (const {name,r,v,w,h,above} of cases) {
  const p=box(r,v,w,h);
  assert(p.left>=v.left+8 && p.left+p.width<=v.left+v.width-8, name+' horizontal bounds');
  assert(p.top>=v.top+8 && p.top+p.height<=v.top+v.height-8, name+' vertical bounds');
  assert(p.width>0 && p.height>0 && p.height<=h, name+' dimensions');
  if(above===true) assert(p.top+p.height<=r.top-8, name+' opens above');
  if(above===false) assert(p.top>=r.bottom+8, name+' opens below');
}
console.log('Split button placement: '+cases.length+' cases passed');
