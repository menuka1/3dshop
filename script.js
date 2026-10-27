// ====== EDIT THESE ======
const WHATSAPP = "94770000000"; // your number, country code first, no + or spaces
const PRODUCTS = [
  {id:1,name:"Ender-style FDM 3D Printer",cat:"3D Printers",price:89900,old:99900,tag:"Best seller"},
  {id:2,name:"Enclosed Core-XY Printer",cat:"3D Printers",price:249000},
  {id:3,name:"Resin Printer 8K",cat:"3D Printers",price:119000,tag:"New"},
  {id:4,name:"PLA Filament 1kg – Black",cat:"Filament",price:5900},
  {id:5,name:"PETG Filament 1kg – Orange",cat:"Filament",price:6900},
  {id:6,name:"TPU Flexible Filament 500g",cat:"Filament",price:7900},
  {id:7,name:"Brass Nozzle Set (0.2–0.8mm)",cat:"Parts",price:2400},
  {id:8,name:"Magnetic Flex Build Plate",cat:"Parts",price:5500,old:6500},
  {id:9,name:"Hotend Kit – All Metal",cat:"Parts",price:8900},
  {id:10,name:"Arduino Uno R3 Kit",cat:"Electronics",price:6400},
  {id:11,name:"Stepper Motor NEMA 17",cat:"Electronics",price:3200},
  {id:12,name:"Digital Calipers 150mm",cat:"Tools",price:3900}
];
// ========================
const $=s=>document.querySelector(s), fmt=n=>"Rs. "+n.toLocaleString("en-LK");
let cart=JSON.parse(localStorage.getItem("cart3d")||"[]"), cat="All", q="";
const cats=["All",...new Set(PRODUCTS.map(p=>p.cat))];
const save=()=>{try{localStorage.setItem("cart3d",JSON.stringify(cart))}catch(e){}};

function renderCats(){
  $("#cats").innerHTML=cats.slice(1).map(c=>`<button class="cat" data-c="${c}"><b>${c}</b><span>${PRODUCTS.filter(p=>p.cat===c).length} items</span></button>`).join("");
  $("#filters").innerHTML=cats.map(c=>`<button role="tab" data-c="${c}" aria-selected="${c===cat}">${c}</button>`).join("");
}
function renderGrid(){
  const list=PRODUCTS.filter(p=>(cat==="All"||p.cat===cat)&&p.name.toLowerCase().includes(q));
  $("#empty").hidden=list.length>0;
  $("#grid").innerHTML=list.map(p=>`<article class="card"><div class="thumb">${p.name.split(" ")[0]}</div><div class="body">
    ${p.tag?`<span class="tag">${p.tag}</span>`:""}<small>${p.cat}</small><h3>${p.name}</h3>
    <div class="price">${fmt(p.price)}${p.old?`<s>${fmt(p.old)}</s>`:""}</div>
    <button data-add="${p.id}">Add to cart</button></div></article>`).join("");
}
function setCat(c){cat=c;renderCats();renderGrid()}
function toast(m){const t=$("#toast");t.textContent=m;t.classList.add("show");clearTimeout(t._t);t._t=setTimeout(()=>t.classList.remove("show"),1800)}
function renderCart(){
  const n=cart.reduce((a,i)=>a+i.q,0), tot=cart.reduce((a,i)=>a+i.q*PRODUCTS.find(p=>p.id===i.id).price,0);
  $("#cartCount").textContent=n; $("#total").textContent=fmt(tot);
  $("#items").innerHTML=cart.length?cart.map(i=>{const p=PRODUCTS.find(x=>x.id===i.id);return `<div class="item"><b>${p.name}</b><span>${fmt(p.price*i.q)}</span>
    <div class="qty"><button data-dec="${i.id}" aria-label="Decrease">−</button>${i.q}<button data-inc="${i.id}" aria-label="Increase">+</button></div>
    <button class="rm" data-rm="${i.id}">Remove</button></div>`}).join(""):"<p>Your cart is empty. Add a product to get started.</p>";
  const lines=cart.map(i=>{const p=PRODUCTS.find(x=>x.id===i.id);return `${i.q} x ${p.name} - ${fmt(p.price*i.q)}`});
  $("#checkout").href=cart.length?`https://wa.me/${WHATSAPP}?text=${encodeURIComponent("Hi 3DShop.lk, I'd like to order:\n"+lines.join("\n")+"\nTotal: "+fmt(tot))}`:"#";
  save();
}
const toggleCart=o=>{$("#drawer").classList.toggle("open",o);$("#drawer").setAttribute("aria-hidden",!o);$("#overlay").hidden=!o};
const change=(id,d)=>{const i=cart.find(x=>x.id===id);if(!i)return;i.q+=d;if(i.q<1)cart=cart.filter(x=>x.id!==id);renderCart()};

document.addEventListener("click",e=>{
  const t=e.target.closest("button,a");if(!t)return;const d=t.dataset;
  if(d.add){const id=+d.add,i=cart.find(x=>x.id===id);i?i.q++:cart.push({id,q:1});renderCart();toast("Added to cart")}
  else if(d.inc)change(+d.inc,1); else if(d.dec)change(+d.dec,-1);
  else if(d.rm){cart=cart.filter(x=>x.id!==+d.rm);renderCart()}
  else if(d.c){setCat(d.c);if(t.classList.contains("cat"))location.hash="shop"}
  else if(t.id==="checkout"&&!cart.length)e.preventDefault();
});
$("#openCart").onclick=()=>toggleCart(true);
$("#closeCart").onclick=$("#overlay").onclick=()=>toggleCart(false);
document.addEventListener("keydown",e=>{if(e.key==="Escape")toggleCart(false)});
$("#menuBtn").onclick=e=>{const o=$("#nav").classList.toggle("open");e.target.setAttribute("aria-expanded",o)};
$("#nav").onclick=()=>$("#nav").classList.remove("open");
$("#searchForm").onsubmit=e=>{e.preventDefault();q=$("#searchInput").value.trim().toLowerCase();setCat("All");location.hash="shop"};
$("#quoteForm").onsubmit=e=>{e.preventDefault();const f=Object.fromEntries(new FormData(e.target));
  const msg=`Quote request\nName: ${f.name}\nPhone: ${f.phone}\nMaterial: ${f.material}\nDetails: ${f.details}`;
  window.open(`https://wa.me/${WHATSAPP}?text=${encodeURIComponent(msg)}`,"_blank");
  $("#quoteNote").textContent="Thanks. Attach your 3D file in the WhatsApp chat that just opened."; e.target.reset()};
$("#waFloat").href=`https://wa.me/${WHATSAPP}?text=${encodeURIComponent("Hi 3DShop.lk, I have a question")}`;
function showTab(n){document.querySelectorAll(".tabs [role=tab]").forEach(b=>{const on=b.id==="tab-"+n;b.setAttribute("aria-selected",on);b.tabIndex=on?0:-1});
  document.querySelectorAll(".panel").forEach(p=>p.hidden=p.id!=="panel-"+n)}
document.querySelectorAll(".tabs [role=tab]").forEach(b=>b.onclick=()=>showTab(b.id.slice(4)));
document.querySelectorAll("[data-tab]").forEach(a=>a.addEventListener("click",()=>showTab(a.dataset.tab)));
document.querySelectorAll("#swatches button").forEach(b=>b.onclick=()=>{
  document.querySelectorAll("#swatches button").forEach(x=>x.setAttribute("aria-checked",x===b));
  $("#cube").style.setProperty("--col",b.dataset.col)});
$("#yr").textContent=new Date().getFullYear();
renderCats();renderGrid();renderCart();
