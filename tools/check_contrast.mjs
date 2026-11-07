import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const css=readFileSync('app/src/main/assets/browser/styles.css','utf8');
function token(name, dark=false){const area=dark?css.slice(css.indexOf(':root[data-theme=dark]')):css.slice(0,css.indexOf(':root[data-theme=dark]'));return area.match(new RegExp(name+':(#[0-9A-Fa-f]{6})'))?.[1]}
function luminance(hex){const rgb=[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16)/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4);return .2126*rgb[0]+.7152*rgb[1]+.0722*rgb[2]}
function ratio(a,b){const pair=[luminance(a),luminance(b)].sort((x,y)=>y-x);return(pair[0]+.05)/(pair[1]+.05)}
for(const dark of [false,true]){const paper=token('--paper',dark),card=token('--card',dark);for(const surface of [paper,card])for(const name of ['--ink','--muted','--accent']){const value=token(name,dark);const contrast=ratio(surface,value);assert.ok(contrast>=4.5,`${name} fails WCAG AA on ${dark?'dark':'light'} ${surface===paper?'paper':'card'}: ${contrast.toFixed(2)}`);console.log(`${dark?'dark':'light'} ${name} on ${surface===paper?'paper':'card'}: ${contrast.toFixed(2)}:1`)}}
