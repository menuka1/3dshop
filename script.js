// ====== EDIT THESE ======
const WHATSAPP = "94774757677";          // your number, country code first, no + or spaces
const PHONE = "+94 77 475 7677";
const EMAIL = "info@3dshop.lk";
const LOCATION = "122/5, Attidiya Road, Bellantara, Dehiwala";
const SALE_ENDS = "2026-11-30T23:59:59"; // flash sale end date (the countdown hides itself after this)
// Categories and sub-categories shown on the shop page
const DEFAULT_CATS = {
  "3D Printers":["FDM","Resin"],
  "Filaments":["PLA","PETG","ABS & ASA","TPU & Flexible"],
  "Resins":["Standard","Engineering"],
  "Printer Parts":["Nozzles","Hotends","Build Plates"],
  "3D Scanners":[],
  "Tools & Accessories":[]
};
// tags: "hot" = Hot deals, "new" = New arrivals, "best" = Best seller. stock: "in" | "out" | "pre"
// old = previous price (shows the discount). Keep ids unique and do not reuse old ids.
// img = photo file name inside images/products/ (for example img:"ender-3.webp"). Without img, a category icon is shown.
const DEFAULT_PRODUCTS = [
  {id:1,name:"Ender-style FDM 3D Printer",cat:"3D Printers",sub:"FDM",price:89900,old:99900,tags:["hot","best"],stock:"in",desc:"Reliable all-rounder for beginners and makers."},
  {id:2,name:"Enclosed Core-XY Printer",cat:"3D Printers",sub:"FDM",price:249000,tags:["best"],stock:"in",desc:"Fast, enclosed and ready for PETG and ABS."},
  {id:3,name:"Resin Printer 8K",cat:"3D Printers",sub:"Resin",price:119000,tags:["new"],stock:"in",desc:"Fine detail for miniatures, jewellery and models."},
  {id:4,name:"Large-Format FDM Printer",cat:"3D Printers",sub:"FDM",price:189000,old:219000,tags:["hot"],stock:"in",desc:"Big build volume for large parts and props."},
  {id:5,name:"Compact Resin Printer",cat:"3D Printers",sub:"Resin",price:74900,old:89900,tags:["hot"],stock:"in",desc:"Small footprint, high detail, easy to start."},
  {id:6,name:"Multi-Colour Enclosed Printer",cat:"3D Printers",sub:"FDM",price:329000,tags:["new"],stock:"pre",desc:"Print in several colours in one job."},
  {id:7,name:"PLA Filament 1kg – Black",cat:"Filaments",sub:"PLA",price:5900,old:7500,tags:["hot","best"],stock:"in",desc:"Easy-printing standard PLA, 1.75 mm."},
  {id:8,name:"PLA+ Filament 1kg – White",cat:"Filaments",sub:"PLA",price:6400,tags:["new"],stock:"in",desc:"Tougher PLA with a clean finish."},
  {id:9,name:"PETG Filament 1kg – Orange",cat:"Filaments",sub:"PETG",price:6900,tags:["best"],stock:"in",desc:"Strong, moisture-resistant, 1.75 mm."},
  {id:10,name:"ABS Filament 1kg – Grey",cat:"Filaments",sub:"ABS & ASA",price:6500,old:7800,tags:["hot"],stock:"in",desc:"Heat-resistant filament for functional parts."},
  {id:11,name:"TPU Flexible Filament 500g",cat:"Filaments",sub:"TPU & Flexible",price:7900,tags:["new"],stock:"in",desc:"Soft, flexible prints for seals and grips."},
  {id:12,name:"Standard Resin 1kg – Grey",cat:"Resins",sub:"Standard",price:8900,tags:["best"],stock:"in",desc:"Sharp detail for general resin printing."},
  {id:13,name:"Tough Engineering Resin 1kg",cat:"Resins",sub:"Engineering",price:12500,tags:["new"],stock:"in",desc:"Strong, impact-resistant functional resin."},
  {id:14,name:"Brass Nozzle Set (0.2–0.8mm)",cat:"Printer Parts",sub:"Nozzles",price:2400,old:3200,tags:["hot"],stock:"in",desc:"Assorted brass nozzles for FDM printers."},
  {id:15,name:"All-Metal Hotend Kit",cat:"Printer Parts",sub:"Hotends",price:8900,tags:["best"],stock:"in",desc:"Upgrade for high-temperature printing."},
  {id:16,name:"Magnetic Flex Build Plate",cat:"Printer Parts",sub:"Build Plates",price:5500,old:6500,tags:["hot"],stock:"in",desc:"Flexible plate, prints pop off easily."},
  {id:17,name:"Handheld 3D Scanner",cat:"3D Scanners",sub:"",price:189000,tags:["new"],stock:"pre",desc:"Scan real objects into 3D models."},
  {id:18,name:"Digital Calipers 150mm",cat:"Tools & Accessories",sub:"",price:3900,tags:["best"],stock:"in",desc:"Accurate measuring for parts and prints."},
  {id:19,name:"Printer Tool Kit",cat:"Tools & Accessories",sub:"",price:4500,tags:["new"],stock:"in",desc:"Scraper, cutters, tweezers and more."},
  {id:20,name:"Filament Dry Box",cat:"Tools & Accessories",sub:"",price:9800,old:11500,tags:["hot"],stock:"out",desc:"Keeps filament dry while printing."}
];
const CATS = window.SHOP_CATALOG?.categories || DEFAULT_CATS;
let PRODUCTS = window.SHOP_CATALOG?.products || DEFAULT_PRODUCTS;
const FEATURED = [1,2,3,4,5]; // printers shown on the home page
// ========================
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)], fmt=n=>"Rs. "+n.toLocaleString("en-LK");
const esc=value=>String(value??"").replace(/[&<>"']/g,char=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[char]));
const page=document.body.dataset.page;
const reduced=matchMedia("(prefers-reduced-motion: reduce)").matches;
const ICONS={
  "3D Printers":'<svg viewBox="0 0 64 64" aria-hidden="true"><rect x="8" y="6" width="48" height="52" rx="4"/><path d="M8 16h48M20 16v20M44 16v20M20 30h24"/><rect x="24" y="30" width="16" height="6"/><path d="M14 50h36"/></svg>',
  "Filaments":'<svg viewBox="0 0 64 64" aria-hidden="true"><circle cx="32" cy="32" r="24"/><circle cx="32" cy="32" r="8"/><path d="M12 22c8 4 32 4 40 0M12 42c8-4 32-4 40 0"/></svg>',
  "Resins":'<svg viewBox="0 0 64 64" aria-hidden="true"><path d="M26 6h12v8l8 10v30a4 4 0 0 1-4 4H22a4 4 0 0 1-4-4V24l8-10z"/><path d="M18 36h28"/></svg>',
  "Printer Parts":'<svg viewBox="0 0 64 64" aria-hidden="true"><path d="M20 8h24v14l-6 6v10l-6 18-6-18V28l-6-6z"/><path d="M26 16h12"/></svg>',
  "3D Scanners":'<svg viewBox="0 0 64 64" aria-hidden="true"><rect x="8" y="16" width="48" height="32" rx="6"/><circle cx="32" cy="32" r="9"/><path d="M14 10h8M42 10h8"/></svg>',
  "Tools & Accessories":'<svg viewBox="0 0 64 64" aria-hidden="true"><path d="M8 40l16-16M30 18l16 16-8 8-16-16zM40 10l14 14"/><path d="M12 52h40"/></svg>'
};
const pct=p=>p.old?Math.round((p.old-p.price)/p.old*100):0;
const slug=v=>{let t=String(v||"").normalize("NFKD").replace(/\p{M}/gu,"").toLowerCase().replace(/&/g," and ").replace(/[^a-z0-9]+/g,"-").replace(/^-+|-+$/g,"");if(t.length>70)t=t.slice(0,70).replace(/-+$/,"");return t||"item"};
const productUrl=p=>`/product/${p.id}-${slug(p.name)}`;

// header, footer and cart drawer are rendered by the server (server/templates) so search engines see them

// ---- cart
let cart=[];try{cart=JSON.parse(localStorage.getItem("cart3d")||"[]")}catch(e){}
cart=cart.filter(i=>PRODUCTS.some(p=>p.id===i.id));
const save=()=>{try{localStorage.setItem("cart3d",JSON.stringify(cart))}catch(e){}};
const prod=id=>PRODUCTS.find(p=>p.id===id);
function toast(m){const t=$("#toast");t.textContent=m;t.classList.add("show");clearTimeout(t._t);t._t=setTimeout(()=>t.classList.remove("show"),1800)}
function renderCart(){
  const n=cart.reduce((a,i)=>a+i.q,0), tot=cart.reduce((a,i)=>a+i.q*prod(i.id).price,0);
  $("#cartCount").textContent=n; $("#total").textContent=fmt(tot);
  $("#items").innerHTML=cart.length?cart.map(i=>{const p=prod(i.id);return `<div class="item"><b>${esc(p.name)}</b><span>${fmt(p.price*i.q)}</span>
    <div class="qty"><button data-dec="${i.id}" aria-label="Decrease">−</button>${i.q}<button data-inc="${i.id}" aria-label="Increase">+</button></div>
    <button class="rm" data-rm="${i.id}">Remove</button></div>`}).join(""):"<p>Your cart is empty. Add a product to get started.</p>";
  const lines=cart.map(i=>`${i.q} x ${prod(i.id).name} - ${fmt(prod(i.id).price*i.q)}`);
  $("#checkout").href=cart.length?`https://wa.me/${WHATSAPP}?text=${encodeURIComponent("Hi 3DSHOP.LK, I'd like to order:\n"+lines.join("\n")+"\nTotal: "+fmt(tot))}`:"#";
  save();
}
const toggleCart=o=>{$("#drawer").classList.toggle("open",o);$("#drawer").setAttribute("aria-hidden",!o);$("#overlay").hidden=!o};
const change=(id,d)=>{const i=cart.find(x=>x.id===id);if(!i)return;i.q+=d;if(i.q<1)cart=cart.filter(x=>x.id!==id);renderCart()};
const STOCK={in:"In stock",out:"Out of stock",pre:"Pre-order"};
// Daraz-style card. Optional per-product fields: rating (0-5), reviews (count), sold (count), free (true = "Free delivery")
const stars=r=>{const f=Math.round(r*2)/2;return [1,2,3,4,5].map(i=>`<i class="${f>=i?"full":f>=i-.5?"half":""}"></i>`).join("")};
const card=p=>{const d=pct(p);const k=n=>n>=1000?(n/1000).toFixed(1).replace(".0","")+"K":n;const icon=ICONS[p.cat]||ICONS["Tools & Accessories"];
  const url=productUrl(p);
  return `<article class="card"><div class="thumb${p.img?" has-img":""}"><a href="${url}" tabindex="-1" aria-hidden="true">${p.img?`<img src="/images/products/${esc(p.img)}" alt="${esc(p.name)}" loading="lazy" width="600" height="600">`:icon}</a>
  ${p.tags.includes("new")||p.tags.includes("best")?`<span class="ribbon${p.tags.includes("new")?"":" best"}">${p.tags.includes("new")?"New":"Best seller"}</span>`:""}
  ${d?`<span class="off">-${d}%</span>`:""}</div>
  <div class="body"><h3><a href="${url}">${esc(p.name)}</a></h3>
  <div class="price">${fmt(p.price)}</div>
  ${p.old?`<div class="was"><s>${fmt(p.old)}</s><b>-${d}%</b></div>`:""}
  ${p.rating?`<div class="rate"><span class="stars" aria-label="${p.rating} out of 5">${stars(p.rating)}</span>${p.reviews?`<em>(${k(p.reviews)})</em>`:""}${p.sold?`<span class="sold">${k(p.sold)} sold</span>`:""}</div>`:""}
  ${p.stock!=="in"?`<span class="stock ${p.stock}">${STOCK[p.stock]}</span>`:p.free?'<span class="stock in">Free delivery</span>':""}
  <button data-add="${p.id}"${p.stock==="out"?" disabled":""}>${p.stock==="out"?"Out of stock":p.stock==="pre"?"Pre-order":"Add to cart"}</button></div></article>`};

document.addEventListener("click",e=>{
  const t=e.target.closest("button");if(!t)return;const d=t.dataset;
  if(d.add){const id=+d.add,i=cart.find(x=>x.id===id);i?i.q++:cart.push({id,q:1});renderCart();toast("Added to cart");const cb=$("#cartCount");cb.classList.remove("bump");void cb.offsetWidth;cb.classList.add("bump");t.classList.add("added");setTimeout(()=>t.classList.remove("added"),900)}
  else if(d.inc)change(+d.inc,1); else if(d.dec)change(+d.dec,-1);
  else if(d.rm){cart=cart.filter(x=>x.id!==+d.rm);renderCart()}
});
$("#checkout").addEventListener("click",e=>{if(!cart.length)e.preventDefault()});
$("#openCart").onclick=()=>toggleCart(true);
$("#closeCart").onclick=$("#overlay").onclick=()=>toggleCart(false);
document.addEventListener("keydown",e=>{if(e.key==="Escape")toggleCart(false)});
$("#menuBtn").onclick=e=>{const o=$("#nav").classList.toggle("open");e.target.setAttribute("aria-expanded",o)};
const closeMenu=()=>{$("#nav").classList.remove("open");$("#menuBtn").setAttribute("aria-expanded",false)};
$("#nav").addEventListener("click",e=>{if(e.target.closest("a"))closeMenu()});
document.addEventListener("click",e=>{if(!e.target.closest(".header-in"))closeMenu()});
document.addEventListener("keydown",e=>{if(e.key==="Escape")closeMenu()});
renderCart();

// ---- home: featured products + portfolio teaser
// ---- auto-scrolling product rails (shop + home)
function initRails(ids){
  const rails={};
  ids.forEach(id=>{
    const el=$("#"+id);if(!el)return;let paused=false;rails[id]=el;
    const step=()=>{const c=el.querySelector(".card");return c?c.offsetWidth+16:300};
    el._next=(dir=1)=>{const max=el.scrollWidth-el.clientWidth;
      if(dir>0&&el.scrollLeft>=max-4)el.scrollTo({left:0,behavior:"smooth"});
      else if(dir<0&&el.scrollLeft<=4)el.scrollTo({left:max,behavior:"smooth"});
      else el.scrollBy({left:dir*step(),behavior:"smooth"})};
    ["mouseenter","focusin","touchstart"].forEach(ev=>el.addEventListener(ev,()=>paused=true,{passive:true}));
    ["mouseleave","focusout"].forEach(ev=>el.addEventListener(ev,()=>paused=false));
    el.addEventListener("touchend",()=>setTimeout(()=>paused=false,4000),{passive:true});
    if(!reduced)setInterval(()=>{if(!paused&&!document.hidden)el._next(1)},3500);
  });
  document.addEventListener("click",e=>{const a=e.target.closest(".rarrow");if(a&&rails[a.dataset.rail])rails[a.dataset.rail]._next(+a.dataset.dir)});
}

// ---- home: featured printers (auto-scrolling)
if($("#featuredRail")){$("#featuredRail").innerHTML=FEATURED.map(id=>prod(id)).filter(Boolean).map(card).join("");initRails(["featuredRail"])}


// ---- portfolio gallery + lightbox

if($("#gallery")||$("#teaser")){
  const lb=document.createElement("dialog");lb.className="lb";lb.setAttribute("aria-label","Project image");
  lb.innerHTML='<button aria-label="Close" autofocus>✕</button><img alt=""><p></p>';document.body.append(lb);
  lb.querySelector("button").onclick=()=>lb.close();
  lb.addEventListener("click",e=>{if(e.target===lb)lb.close()});
  document.addEventListener("click",e=>{const w=e.target.closest(".work");if(!w)return;
    lb.querySelector("img").src="/images/"+w.dataset.img+".webp";lb.querySelector("img").alt=w.dataset.t;lb.querySelector("p").textContent=w.dataset.t;lb.showModal()});
}

// ---- shop page
if($("#grid")){
  let cat="All",sub="",tab="all",q="",sort="feat";
  const tabs=[["all","All"],["new","New arrivals"],["hot","Hot deals"],["best","Best sellers"]];
  const count=(c,s)=>PRODUCTS.filter(p=>p.cat===c&&(!s||p.sub===s)).length;

  // category tiles
  $("#catTiles").innerHTML=Object.keys(CATS).map(c=>`<button class="cat-tile" data-cat="${esc(c)}">${ICONS[c]||ICONS["Tools & Accessories"]}<b>${esc(c)}</b><span>${count(c)} items</span></button>`).join("");

  // sidebar tree
  const drawSide=()=>{
    $("#sideCats").innerHTML=`<button class="side-all" data-cat="All" aria-current="${cat==="All"}">All products <span>${PRODUCTS.length}</span></button>`+
      Object.entries(CATS).map(([c,subs])=>`<div class="side-grp"><button data-cat="${esc(c)}" aria-current="${cat===c&&!sub}">${esc(c)} <span>${count(c)}</span></button>`+
      (cat===c&&subs.length?`<div class="side-sub">${subs.map(s=>`<button data-cat="${esc(c)}" data-sub="${esc(s)}" aria-current="${sub===s}">${esc(s)} <span>${count(c,s)}</span></button>`).join("")}</div>`:"")+`</div>`).join("");
  };
  const draw=()=>{
    $("#tabs").innerHTML=tabs.map(t=>`<button role="tab" data-tab="${t[0]}" aria-selected="${t[0]===tab}">${t[1]}</button>`).join("");
    let list=PRODUCTS.filter(p=>(cat==="All"||p.cat===cat)&&(!sub||p.sub===sub)&&(tab==="all"||p.tags.includes(tab))&&(p.name+" "+p.desc+" "+p.cat+" "+p.sub).toLowerCase().includes(q));
    const by={low:(a,b)=>a.price-b.price,high:(a,b)=>b.price-a.price,disc:(a,b)=>pct(b)-pct(a),name:(a,b)=>a.name.localeCompare(b.name)}[sort];
    if(by)list=[...list].sort(by);
    $("#empty").hidden=list.length>0;$("#grid").innerHTML=list.map(card).join("");
    $("#count").textContent=`${list.length} product${list.length===1?"":"s"}${cat!=="All"?" in "+cat+(sub?" › "+sub:""):""}`;
    drawSide();
  };
  const setCat=(c,s="")=>{cat=c;sub=s;draw()};
  document.addEventListener("click",e=>{
    const b=e.target.closest("button[data-cat],button[data-tab],a[data-go-cat]");if(!b)return;
    if(b.dataset.tab){tab=b.dataset.tab;draw()}
    else if(b.tagName==="A"){setCat(b.dataset.goCat)}
    else{setCat(b.dataset.cat,b.dataset.sub||"");if(b.classList.contains("cat-tile"))$("#all").scrollIntoView({behavior:reduced?"auto":"smooth"})}
  });
  $("#sort").onchange=e=>{sort=e.target.value;draw()};
  $("#searchForm").onsubmit=e=>{e.preventDefault();q=$("#searchInput").value.trim().toLowerCase();cat="All";sub="";tab="all";draw();$("#all").scrollIntoView({behavior:reduced?"auto":"smooth"})};
  draw();

  // hot deals + new arrivals rails (auto-scrolling)
  const hot=PRODUCTS.filter(p=>p.tags.includes("hot")).sort((a,b)=>pct(b)-pct(a));
  $("#hotRail").innerHTML=hot.map(card).join("");
  $("#newRail").innerHTML=PRODUCTS.filter(p=>p.tags.includes("new")).map(card).join("");
  initRails(["hotRail","newRail"]);

  // flash-sale countdown
  const end=new Date(SALE_ENDS).getTime();
  const tick=()=>{
    const ms=end-Date.now(),box=$("#countdownBox");
    if(isNaN(end)||ms<=0){box.hidden=true;return false}
    const d=Math.floor(ms/864e5),h=Math.floor(ms/36e5)%24,m=Math.floor(ms/6e4)%60,s=Math.floor(ms/1e3)%60,z=n=>String(n).padStart(2,"0");
    $("#countdown").textContent=`${d}d ${z(h)}h ${z(m)}m ${z(s)}s`;box.hidden=false;return true};
  if(tick()){const iv=setInterval(()=>{if(!tick())clearInterval(iv)},1000)}

  // hero slider
  const slides=$$("#slider .slide");let si=0,sp=false;
  $("#dots").innerHTML=slides.map((_,i)=>`<button aria-label="Go to slide ${i+1}" data-slide="${i}"></button>`).join("");
  const go=n=>{si=(n+slides.length)%slides.length;
    slides.forEach((s,i)=>{s.classList.toggle("on",i===si);s.setAttribute("aria-hidden",i!==si);s.querySelectorAll("a").forEach(a=>a.tabIndex=i===si?0:-1)});
    $$("#dots button").forEach((d,i)=>d.setAttribute("aria-current",i===si))};
  $("#slPrev").onclick=()=>go(si-1);$("#slNext").onclick=()=>go(si+1);
  $("#dots").onclick=e=>{const b=e.target.closest("button");if(b)go(+b.dataset.slide)};
  const sl=$("#slider");["mouseenter","focusin"].forEach(ev=>sl.addEventListener(ev,()=>sp=true));["mouseleave","focusout"].forEach(ev=>sl.addEventListener(ev,()=>sp=false));
  go(0);if(!reduced)setInterval(()=>{if(!sp&&!document.hidden)go(si+1)},5000);
}


// ---- How it works: steps are static HTML; animate them in when scrolled into view
if($("#how-steps")){
  if("IntersectionObserver" in window){
    const io=new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting){e.target.classList.add("in");io.unobserve(e.target)}}),{threshold:.25});
    io.observe($("#how-steps"));
  }else $("#how-steps").classList.add("in");
}

// ---- tabs (home)
function showTab(n){$$(".tabs [role=tab]").forEach(b=>{const on=b.id==="tab-"+n;b.setAttribute("aria-selected",on);b.tabIndex=on?0:-1});
  $$(".panel").forEach(p=>p.hidden=p.id!=="panel-"+n)}
$$(".tabs [role=tab]").forEach(b=>b.onclick=()=>showTab(b.id.slice(4)));

// ---- colour preview (services)
$$("#swatches button").forEach(b=>b.onclick=()=>{
  $$("#swatches button").forEach(x=>x.setAttribute("aria-checked",x===b));
  $("#cube").style.setProperty("--col",b.dataset.col)});

// ---- quote form -> private inbox, then WhatsApp
if($("#quoteForm"))$("#quoteForm").onsubmit=async e=>{
  e.preventDefault();
  const form=e.target, button=form.querySelector('button[type="submit"]'), note=$("#quoteNote");
  const chat=window.open("about:blank","_blank");
  button.disabled=true;note.textContent="Sending your quote request…";
  try{
    const response=await fetch("/api/quote-requests",{method:"POST",body:new FormData(form)});
    const result=await response.json().catch(()=>({}));
    if(!response.ok)throw new Error(result.error||"Could not submit your request. Please try again.");
    const formData=Object.fromEntries(new FormData(form));
    const msg=`Quote request ${result.ref}\nType: ${formData.type}\nName: ${formData.name}\nPhone: ${formData.phone}\nMaterial: ${formData.material}\nColour: ${formData.colour}\nDetails: ${formData.details}`;
    const whatsappUrl=`https://wa.me/${WHATSAPP}?text=${encodeURIComponent(msg)}`;
    if(chat)chat.location.href=whatsappUrl;
    note.textContent=`Request ${result.ref} received. We have your details${form.querySelector('[name="files"]').files.length?" and files":""}. `;
    const continueLink=document.createElement("a");continueLink.href=whatsappUrl;continueLink.target="_blank";continueLink.rel="noopener";continueLink.textContent="Continue to WhatsApp";note.append(continueLink);
    form.reset();
  }catch(error){
    if(chat)chat.close();
    note.textContent=error.message;
  }finally{button.disabled=false}
};

// ---- quote page: pre-fill from ?type= and ?estimate=
if($("#quoteForm")){
  const qp=new URLSearchParams(location.search);
  if(qp.get("type")==="design")$("#typeSel").value="Design and print";
  const est=qp.get("estimate");
  if(est)$('#quoteForm [name="details"]').value=est;
}

// ---- contact page details
$$("[data-phone]").forEach(a=>{a.textContent=PHONE;a.href="tel:"+PHONE.replace(/\s/g,"")});
$$("[data-email]").forEach(a=>{a.textContent=EMAIL;a.href="mailto:"+EMAIL});
$$("[data-wa]").forEach(a=>a.href=`https://wa.me/${WHATSAPP}`);

// ---- motion: scroll reveal, header shadow, hero parallax
(()=>{
  const hdr=$(".header");
  const onScroll=()=>{hdr.classList.toggle("scrolled",scrollY>8)};
  addEventListener("scroll",onScroll,{passive:true});onScroll();
  if(reduced||!("IntersectionObserver" in window))return;
  const sel=".section .head-row,.section>.wrap>h2,.section>.wrap>.h-sm,.strip>div,.cat-tile,.work,.panel,.tabs,.why article,.steps li,.svc article,.cards3 article,.apps article,.faq details,.quote,.two>*,.colour>*,.cta-band .wrap>*,.sidebar,.toolbar";
  const io=new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting){e.target.classList.add("in");io.unobserve(e.target)}}),{threshold:.12,rootMargin:"0px 0px -40px 0px"});
  $$(sel).forEach(el=>{
    const sibs=[...el.parentElement.children].filter(c=>c.matches(sel));
    el.style.setProperty("--d",Math.min(sibs.indexOf(el),6)*70+"ms");
    el.classList.add("reveal");io.observe(el);
  });
  const hi=$(".hero-img");
  if(hi&&matchMedia("(min-width:901px)").matches)addEventListener("scroll",()=>{hi.style.setProperty("--py",Math.min(scrollY,400)*-.06+"px")},{passive:true});
})();
