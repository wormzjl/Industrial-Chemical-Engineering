// node tools/localization/width.js "<text>"... : a line's width in GUI pixels in the vanilla default font, as
// UnitTextTest.width counts it (ascii advances; full-width glyphs 9; anything else 6).
const narrow={' ':4,'!':2,"'":2,',':2,'.':2,':':2,';':2,'i':2,'|':2,'`':3,'l':3,'"':4,'(':4,')':4,'*':4,'I':4,'[':4,']':4,'t':4,'{':4,'}':4,'<':5,'>':5,'f':5,'k':5,'@':7,'~':7};
const full=c=>c>=0x2E80&&c<=0x9FFF||c>=0xAC00&&c<=0xD7A3||c>=0xF900&&c<=0xFAFF||c>=0xFF01&&c<=0xFF60;
const width=s=>[...s].reduce((w,ch)=>w+(narrow[ch]??(full(ch.charCodeAt(0))?9:6)),0);
module.exports=width;if(require.main===module)for(const t of process.argv.slice(2))console.log(width(t),t);
