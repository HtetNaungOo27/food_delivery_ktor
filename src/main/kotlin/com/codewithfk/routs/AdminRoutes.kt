package com.codewithfk.routs

import com.codewithfk.model.*
import com.codewithfk.services.AdminService
import com.codewithfk.services.AuthService
import com.codewithfk.utils.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.adminPublicRoutes() {
    get("/admin") { call.respondText(adminDashboardHtml, ContentType.Text.Html) }
    post("/admin/auth/login") {
        val request = call.receive<AdminLoginRequest>()
        val token = AuthService.login(request.email.trim(), request.password, UserRole.ADMIN)
            ?: return@post call.respondError(HttpStatusCode.Unauthorized, "Invalid administrator credentials")
        call.respond(mapOf("token" to token))
    }
}

fun Route.adminRoutes() {
    route("/admin/api") {
        get("/summary") {
            if (call.adminIdOrNull() == null) return@get
            call.respond(AdminService.summary())
        }
        get("/users") {
            if (call.adminIdOrNull() == null) return@get
            call.respond(mapOf("data" to AdminService.users(call.pageLimit(), call.pageOffset(), call.request.queryParameters["role"])))
        }
        get("/orders") {
            if (call.adminIdOrNull() == null) return@get
            call.respond(mapOf("data" to AdminService.orders(call.pageLimit(), call.pageOffset(), call.request.queryParameters["status"])))
        }
        get("/restaurants") {
            if (call.adminIdOrNull() == null) return@get
            call.respond(mapOf("data" to AdminService.restaurants()))
        }
        get("/commission") {
            if (call.adminIdOrNull() == null) return@get
            call.respond(AdminService.commissionOverview())
        }
        get("/disputes") {
            if (call.adminIdOrNull() == null) return@get
            call.respond(mapOf("data" to AdminService.disputes(call.pageLimit(), call.pageOffset(), call.request.queryParameters["status"])))
        }
        get("/audit-logs") {
            if (call.adminIdOrNull() == null) return@get
            call.respond(mapOf("data" to AdminService.auditLogs()))
        }
        post("/users/{id}/status") {
            val adminId = call.adminIdOrNull() ?: return@post
            call.adminAction { AdminService.setUserActive(adminId, UUID.fromString(call.parameters["id"]), call.receive<SetActiveRequest>().active) }
        }
        post("/restaurants/{id}/approval") {
            val adminId = call.adminIdOrNull() ?: return@post
            call.adminAction { AdminService.setRestaurantApproved(adminId, UUID.fromString(call.parameters["id"]), call.receive<SetApprovalRequest>().approved) }
        }
        post("/commission") {
            val adminId = call.adminIdOrNull() ?: return@post
            call.adminAction { AdminService.setCommission(adminId, call.receive<CommissionRequest>().percentage) }
        }
        post("/disputes/{id}/resolve") {
            val adminId = call.adminIdOrNull() ?: return@post
            val request = call.receive<ResolveDisputeRequest>()
            call.adminAction { AdminService.resolveDispute(adminId, UUID.fromString(call.parameters["id"]), request.status, request.resolution) }
        }
        post("/orders/{id}/refund") {
            val adminId = call.adminIdOrNull() ?: return@post
            val request = call.receive<AdminRefundRequest>()
            call.adminAction { AdminService.refundOrder(adminId, UUID.fromString(call.parameters["id"]), request.reason) }
        }
        get("/payouts") { if (call.adminIdOrNull() == null) return@get; call.respond(mapOf("data" to com.codewithfk.services.PayoutService.all(call.request.queryParameters["status"]))) }
        post("/payouts/{id}") { if (call.adminIdOrNull() == null) return@post; call.adminAction { com.codewithfk.services.PayoutService.process(UUID.fromString(call.parameters["id"]), call.receive<ProcessPayoutRequest>()) } }
    }
}

private fun io.ktor.server.application.ApplicationCall.pageLimit() = request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
private fun io.ktor.server.application.ApplicationCall.pageOffset() = request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0

private suspend fun io.ktor.server.application.ApplicationCall.adminIdOrNull(): UUID? {
    val id = principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
    val adminId = runCatching { UUID.fromString(id) }.getOrNull()
    if (adminId == null || !AuthService.hasAnyRole(adminId, setOf(UserRole.ADMIN))) {
        respondError(HttpStatusCode.Forbidden, "Administrator access required")
        return null
    }
    return adminId
}

private suspend fun io.ktor.server.application.ApplicationCall.adminAction(action: suspend () -> Unit) {
    try { action(); respond(mapOf("success" to true)) }
    catch (e: IllegalArgumentException) { respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid request") }
    catch (e: IllegalStateException) { respondError(HttpStatusCode.Conflict, e.message ?: "Action cannot be completed") }
}

private val adminDashboardHtml = """
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SwiftBite Admin</title><style>
:root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,sans-serif;color:#17211f;background:#f4f7f6;--brand:#087f69;--ink:#17211f;--muted:#65716d;--line:#dce6e2;--surface:#fff;--soft:#e8f4f1;--danger:#b3261e}*{box-sizing:border-box}body{margin:0;background:linear-gradient(180deg,#eef7f4 0,#f7f9f8 280px);min-height:100vh}main{max-width:1280px;margin:auto;padding:32px 28px 56px}h1{font-size:clamp(24px,3vw,32px);letter-spacing:-.03em;margin:0}h2{font-size:18px;margin:0}p{color:var(--muted);line-height:1.55}.top{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:28px}.brand{display:flex;align-items:center;gap:12px}.brand-mark{display:grid;place-items:center;width:44px;height:44px;border-radius:14px;background:var(--brand);color:#fff;font-weight:800;box-shadow:0 8px 22px #087f6933}.card{background:var(--surface);border:1px solid var(--line);border-radius:20px;padding:20px;box-shadow:0 8px 30px #193d3410}.login{max-width:430px;margin:10vh auto;padding:30px}.login button{width:100%}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:14px}.metric{position:relative;overflow:hidden}.metric:after{content:'';position:absolute;right:-18px;bottom:-28px;width:80px;height:80px;border-radius:50%;background:var(--soft)}.metric span{color:var(--muted);font-size:13px;font-weight:650}.metric b{display:block;font-size:27px;margin-top:8px;letter-spacing:-.04em}.workspace{display:grid;grid-template-columns:190px minmax(0,1fr);gap:18px;margin-top:22px}.tabs{display:flex;flex-direction:column;gap:6px;margin:0;padding:10px;height:max-content;position:sticky;top:20px}.tabs button{justify-content:flex-start;text-align:left;background:transparent;color:var(--muted)}.tabs button.active{background:var(--soft);color:#075c4e}.content-head{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:14px}.content-head input{max-width:300px;margin:0}.table-wrap{padding:4px 18px 10px;overflow:auto}button{display:inline-flex;align-items:center;justify-content:center;min-height:46px;border:0;border-radius:12px;padding:0 17px;font:inherit;font-size:14px;font-weight:700;cursor:pointer;background:var(--brand);color:white;transition:transform .15s,box-shadow .15s,background .15s}button:hover{transform:translateY(-1px);box-shadow:0 8px 18px #087f6924}button:focus-visible,input:focus-visible{outline:3px solid #48bca45c;outline-offset:2px}button.secondary{background:var(--soft);color:#075c4e}button.danger{background:#fce9e7;color:#9d2820}button.small{min-height:38px;padding:0 12px;font-size:12px}input{width:100%;min-height:52px;margin:6px 0 14px;padding:0 14px;border:1px solid #cbd8d4;border-radius:12px;background:#fff;font:inherit}table{width:100%;border-collapse:collapse;min-width:720px}th,td{text-align:left;padding:15px 10px;border-bottom:1px solid #edf1ef;font-size:13px;vertical-align:middle}th{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.07em;position:sticky;top:0;background:#fff}tbody tr:hover{background:#f8fbfa}.badge{display:inline-flex;border-radius:999px;padding:5px 9px;background:#edf2f0;color:#4f5f5a;font-size:11px;font-weight:750}.badge.good{background:#ddf4eb;color:#087052}.badge.warn{background:#fff1cf;color:#7a5400}.badge.bad{background:#fce6e4;color:#9d2820}.hidden{display:none}.error{color:var(--danger)}#notice{min-height:24px;margin:8px 0;color:#087052;font-weight:650}@media(max-width:820px){main{padding:22px 16px 40px}.workspace{grid-template-columns:1fr}.tabs{position:static;flex-direction:row;overflow:auto}.tabs button{white-space:nowrap}.top{align-items:flex-start}.content-head{align-items:stretch;flex-direction:column}.content-head input{max-width:none}}@media(max-width:520px){.top{flex-direction:column}.top>button{width:100%}.grid{grid-template-columns:repeat(2,1fr)}.login{margin:5vh auto;padding:24px}}
.commission-panel{padding:28px;max-width:760px}.commission-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin:22px 0}.commission-field{max-width:320px}.commission-field label{display:block;font-weight:750}.rate-input{position:relative}.rate-input input{padding-right:44px;font-size:20px;font-weight:750}.rate-input span{position:absolute;right:16px;top:22px;color:var(--muted);font-weight:750}.preview{background:var(--soft);border-radius:16px;padding:18px}.preview-row{display:flex;justify-content:space-between;gap:16px;padding:8px 0}.preview-row+.preview-row{border-top:1px solid #cfe2dc}.info-note{border-left:4px solid var(--brand);background:#f1f8f6;padding:13px 15px;border-radius:0 12px 12px 0;color:#40514c;font-size:13px}.form-actions{display:flex;align-items:center;gap:12px;margin-top:20px}.field-error{color:var(--danger);font-size:12px;min-height:18px}button:disabled{opacity:.58;cursor:not-allowed;transform:none;box-shadow:none}.hidden{display:none!important}@media(max-width:520px){.commission-grid{grid-template-columns:1fr}.commission-panel{padding:20px}.form-actions{align-items:stretch;flex-direction:column}.form-actions button{width:100%}}
</style></head><body><main><section id="login" class="card login"><div class="brand"><div class="brand-mark">SB</div><div><h1>SwiftBite Admin</h1><p style="margin:2px 0 0">Operations console</p></div></div><p>Sign in securely to manage the delivery marketplace.</p><label>Email address</label><input id="email" type="email" autocomplete="username" placeholder="admin@swiftbite.mm"><label>Password</label><input id="password" type="password" autocomplete="current-password" placeholder="Enter your password"><button id="loginButton" onclick="login()">Sign in</button><p id="error" class="error" role="alert"></p></section>
<section id="app" class="hidden"><div class="top"><div class="brand"><div class="brand-mark">SB</div><div><h1>Operations overview</h1><p style="margin:2px 0 0">Customers, partners, deliveries and payments</p></div></div><button class="secondary" onclick="logout()">Sign out</button></div><div id="metrics" class="grid"></div><div class="workspace"><nav class="card tabs" aria-label="Admin sections"><button data-tab="users" class="active" onclick="loadUsers(this)">Users</button><button data-tab="restaurants" onclick="loadRestaurants(this)">Restaurants</button><button data-tab="orders" onclick="loadOrders(this)">Orders</button><button data-tab="disputes" onclick="loadDisputes(this)">Disputes</button><button data-tab="audit" onclick="loadAudit(this)">Audit log</button><button data-tab="commission" onclick="editCommission(this)">Commission</button></nav><section><div class="content-head"><div><h2 id="sectionTitle">Users</h2><p id="notice"></p></div><div style="display:flex;gap:8px"><input id="tableSearch" type="search" placeholder="Search this view" oninput="filterRows()" aria-label="Search current table"><button id="exportButton" class="secondary" onclick="exportCsv()">Export CSV</button></div></div><div id="dataCard" class="card table-wrap"><table><thead id="head"></thead><tbody id="rows"></tbody></table></div><div id="commissionPanel" class="card commission-panel hidden"></div></section></div></section></main>
<script>
const loginView=document.getElementById('login'),appView=document.getElementById('app'),emailInput=document.getElementById('email'),passwordInput=document.getElementById('password'),errorView=document.getElementById('error'),metricsView=document.getElementById('metrics'),headView=document.getElementById('head'),rowsView=document.getElementById('rows'),noticeView=document.getElementById('notice'),sectionTitle=document.getElementById('sectionTitle'),tableSearch=document.getElementById('tableSearch'),loginButton=document.getElementById('loginButton'),dataCard=document.getElementById('dataCard'),commissionPanel=document.getElementById('commissionPanel');
let token=localStorage.getItem('swiftbite_admin_token'),page=0; const pageSize=25,fmt=n=>new Intl.NumberFormat('en-US').format(n*1000)+' Ks';
const pager=document.createElement('div');pager.style='display:flex;gap:8px;align-items:center;justify-content:flex-end;margin-top:12px';dataCard.after(pager);
function showDetails(item){alert(Object.entries(item).map(([key,value])=>key.replace(/([A-Z])/g,' $1')+': '+(value??'—')).join('\n'))}
function reloadPage(){const title=sectionTitle.textContent;if(title==='Users')loadUsers();else if(title==='Orders')loadOrders();else if(title==='Disputes')loadDisputes()}
function renderPager(count){pager.innerHTML=`<button class="small secondary" ${'$'}{page===0?'disabled':''} onclick="page--;reloadPage()">Previous</button><span>Page ${'$'}{page+1}</span><button class="small secondary" ${'$'}{count<pageSize?'disabled':''} onclick="page++;reloadPage()">Next</button>`}
new MutationObserver(()=>rowsView.querySelectorAll('tr').forEach(row=>{row.title='Click to view record details';row.style.cursor='pointer';row.onclick=e=>{if(e.target.closest('button'))return;alert([...row.cells].map((cell,index)=>(headView.querySelectorAll('th')[index]?.textContent||'Field')+': '+cell.textContent.trim()).join('\n'))}})).observe(rowsView,{childList:true});
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const badge=(value,tone='')=>`<span class="badge ${'$'}{tone}">${'$'}{esc(value)}</span>`;
function selectTab(button,title){document.querySelectorAll('[data-tab]').forEach(x=>x.classList.remove('active'));if(button)button.classList.add('active');sectionTitle.textContent=title;tableSearch.value='';noticeView.textContent='';if(title!=='Commission'){dataCard.classList.remove('hidden');commissionPanel.classList.add('hidden');tableSearch.classList.remove('hidden')}}
function filterRows(){const q=tableSearch.value.trim().toLowerCase();rowsView.querySelectorAll('tr').forEach(row=>row.hidden=!row.textContent.toLowerCase().includes(q))}
function exportCsv(){const rows=[...dataCard.querySelectorAll('tr')].filter(r=>!r.hidden).map(row=>[...row.querySelectorAll('th,td')].map(cell=>'"'+cell.textContent.trim().replaceAll('"','""')+'"').join(','));if(!rows.length)return;const blob=new Blob([rows.join('\n')],{type:'text/csv;charset=utf-8'}),link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download='swiftbite-'+sectionTitle.textContent.toLowerCase().replaceAll(' ','-')+'.csv';link.click();URL.revokeObjectURL(link.href)}
async function api(path,options={}){if(options.method==null&&['/admin/api/users','/admin/api/orders','/admin/api/disputes'].includes(path))path+=`?limit=${'$'}{pageSize}&offset=${'$'}{page*pageSize}`;const r=await fetch(path,{...options,headers:{'Content-Type':'application/json','Authorization':'Bearer '+token,...options.headers}});if(r.status===401||r.status===403){logout();throw Error('Administrator access required')}if(!r.ok)throw Error(await r.text());const result=await r.json();if(result.data&&Array.isArray(result.data)&&['Users','Orders','Disputes'].includes(sectionTitle.textContent))setTimeout(()=>renderPager(result.data.length));return result}
async function login(){errorView.textContent='';loginButton.disabled=true;loginButton.textContent='Signing in…';try{const r=await fetch('/admin/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:emailInput.value.trim(),password:passwordInput.value})});if(!r.ok)throw Error('Invalid administrator credentials');token=(await r.json()).token;localStorage.setItem('swiftbite_admin_token',token);await start()}catch(e){errorView.textContent=e.message}finally{loginButton.disabled=false;loginButton.textContent='Sign in'}}
function logout(){localStorage.removeItem('swiftbite_admin_token');token=null;appView.classList.add('hidden');loginView.classList.remove('hidden')}
async function start(){loginView.classList.add('hidden');appView.classList.remove('hidden');try{const s=await api('/admin/api/summary');metricsView.innerHTML=[['Customers',s.customers],['Owners',s.owners],['Riders',s.riders],['Restaurants',s.restaurants],['Active orders',s.activeOrders],['Delivered',s.deliveredOrders],['Delivered GMV',fmt(s.grossMerchandiseValue)]].map(x=>`<div class="card metric"><span>${'$'}{x[0]}</span><b>${'$'}{x[1]}</b></div>`).join('');await loadUsers()}catch(e){errorView.textContent=e.message}}
async function loadUsers(button){selectTab(button||document.querySelector('[data-tab="users"]'),'Users');const x=(await api('/admin/api/users')).data;headView.innerHTML='<tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th>Action</th></tr>';rowsView.innerHTML=x.map(i=>`<tr><td><b>${'$'}{esc(i.name)}</b></td><td>${'$'}{esc(i.email)}</td><td>${'$'}{badge(i.role)}</td><td>${'$'}{badge(i.isActive?'Active':'Suspended',i.isActive?'good':'bad')}</td><td>${'$'}{i.role==='ADMIN'?'Protected':`<button class="small ${'$'}{i.isActive?'danger':'secondary'}" onclick="setUser('${'$'}{i.id}',${'$'}{!i.isActive})">${'$'}{i.isActive?'Suspend':'Activate'}</button>`}</td></tr>`).join('')}
async function setUser(id,active){if(!confirm(`${'$'}{active?'Activate':'Suspend'} this account?`))return;await api(`/admin/api/users/${'$'}{id}/status`,{method:'POST',body:JSON.stringify({active})});loadUsers()}
async function loadRestaurants(button){selectTab(button,'Restaurants');const x=(await api('/admin/api/restaurants')).data;headView.innerHTML='<tr><th>Restaurant</th><th>Owner</th><th>Store</th><th>Approval</th><th>Action</th></tr>';rowsView.innerHTML=x.map(i=>`<tr><td><b>${'$'}{esc(i.name)}</b></td><td>${'$'}{esc(i.owner)}</td><td>${'$'}{badge(i.isOpen?'Open':'Closed',i.isOpen?'good':'warn')}</td><td>${'$'}{badge(i.isApproved?'Approved':'Suspended',i.isApproved?'good':'bad')}</td><td><button class="small ${'$'}{i.isApproved?'danger':'secondary'}" onclick="setRestaurant('${'$'}{i.id}',${'$'}{!i.isApproved})">${'$'}{i.isApproved?'Suspend':'Approve'}</button></td></tr>`).join('')}
async function setRestaurant(id,approved){if(!confirm(`${'$'}{approved?'Approve':'Suspend'} this restaurant?`))return;await api(`/admin/api/restaurants/${'$'}{id}/approval`,{method:'POST',body:JSON.stringify({approved})});loadRestaurants()}
async function loadOrders(button){selectTab(button,'Orders');const x=(await api('/admin/api/orders')).data;headView.innerHTML='<tr><th>Order</th><th>Customer</th><th>Restaurant</th><th>Status</th><th>Payment</th><th>Total</th><th>Commission</th><th>Action</th></tr>';rowsView.innerHTML=x.map(i=>`<tr><td><b>#${'$'}{esc(i.id.slice(-8))}</b></td><td>${'$'}{esc(i.customer)}</td><td>${'$'}{esc(i.restaurant)}</td><td>${'$'}{badge(i.status,i.status==='DELIVERED'?'good':'warn')}</td><td>${'$'}{badge(i.paymentMethod+' · '+i.paymentStatus,i.paymentStatus==='PAID'?'good':'')}</td><td>${'$'}{fmt(i.totalAmount)}</td><td>${'$'}{fmt(i.commissionAmount)}</td><td>${'$'}{i.paymentStatus==='PAID'&&i.paymentMethod!=='COD'?`<button class="small danger" onclick="refund('${'$'}{i.id}')">Refund</button>`:''}</td></tr>`).join('')}
async function refund(id){const reason=prompt('Reason for this refund');if(!reason)return;await api(`/admin/api/orders/${'$'}{id}/refund`,{method:'POST',body:JSON.stringify({reason})});loadOrders()}
async function loadDisputes(button){selectTab(button,'Disputes');const x=(await api('/admin/api/disputes')).data;headView.innerHTML='<tr><th>Customer</th><th>Subject</th><th>Description</th><th>Status</th><th>Action</th></tr>';rowsView.innerHTML=x.map(i=>`<tr><td><b>${'$'}{esc(i.customer)}</b></td><td>${'$'}{esc(i.subject)}</td><td>${'$'}{esc(i.description)}</td><td>${'$'}{badge(i.status,i.status==='OPEN'?'warn':'good')}</td><td>${'$'}{i.status==='OPEN'?`<button class="small" onclick="resolveDispute('${'$'}{i.id}')">Resolve</button>`:esc(i.resolution||'')}</td></tr>`).join('')}
async function loadAudit(button){selectTab(button,'Audit log');const x=(await api('/admin/api/audit-logs')).data;headView.innerHTML='<tr><th>Time</th><th>Administrator</th><th>Action</th><th>Target</th><th>Details</th></tr>';rowsView.innerHTML=x.map(i=>`<tr><td>${'$'}{esc(i.createdAt.replace('T',' '))}</td><td><b>${'$'}{esc(i.administrator)}</b></td><td>${'$'}{badge(i.action)}</td><td>${'$'}{esc(i.targetType)} · ${'$'}{esc(i.targetId)}</td><td>${'$'}{esc(i.details||'—')}</td></tr>`).join('')}
async function resolveDispute(id){const resolution=prompt('Resolution notes');if(!resolution)return;await api(`/admin/api/disputes/${'$'}{id}/resolve`,{method:'POST',body:JSON.stringify({status:'RESOLVED',resolution})});loadDisputes()}
async function editCommission(button){selectTab(button||document.querySelector('[data-tab="commission"]'),'Commission');dataCard.classList.add('hidden');tableSearch.classList.add('hidden');commissionPanel.classList.remove('hidden');commissionPanel.innerHTML='<p>Loading commission settings…</p>';try{const c=await api('/admin/api/commission');commissionPanel.innerHTML=`<h2>Platform commission</h2><p>Track what SwiftBite earns and set the rate charged on newly placed orders.</p><div class="commission-grid"><div class="preview"><span>Earned from delivered orders</span><div class="preview-row"><strong>${'$'}{fmt(c.earnedAmount)}</strong><span>${'$'}{c.earnedOrderCount} orders</span></div></div><div class="preview"><span>Projected from active orders</span><div class="preview-row"><strong>${'$'}{fmt(c.projectedAmount)}</strong><span>${'$'}{c.projectedOrderCount} orders</span></div></div></div><div class="commission-grid"><div class="commission-field"><label for="commissionRate">Commission rate for new orders</label><div class="rate-input"><input id="commissionRate" type="number" min="0" max="50" step="0.5" value="${'$'}{Number(c.percentage)}" oninput="updateCommissionPreview()"><span>%</span></div><div id="commissionError" class="field-error"></div></div><div class="preview"><b>Example for a 20,000 Ks order</b><div class="preview-row"><span>SwiftBite receives</span><strong id="commissionPreview">0 Ks</strong></div><div class="preview-row"><span>Restaurant receives</span><strong id="partnerPreview">0 Ks</strong></div></div></div><div class="info-note"><b>The exact amount is saved on every order.</b> Delivered-order commission is counted as earned; active-order commission remains projected. Rate changes affect future orders only.</div><div class="form-actions"><button id="saveCommissionButton" onclick="saveCommission()">Save commission rate</button><span id="commissionSaved" role="status"></span></div>`;updateCommissionPreview()}catch(e){commissionPanel.innerHTML=`<p class="error">${'$'}{esc(e.message)}</p><button onclick="editCommission()">Try again</button>`}}
function updateCommissionPreview(){const input=document.getElementById('commissionRate'),error=document.getElementById('commissionError'),save=document.getElementById('saveCommissionButton');if(!input)return;const rate=Number(input.value),valid=Number.isFinite(rate)&&rate>=0&&rate<=50;error.textContent=valid?'':'Enter a rate from 0% to 50%';save.disabled=!valid;const fee=valid?20000*rate/100:0;document.getElementById('commissionPreview').textContent=new Intl.NumberFormat('en-US').format(fee)+' Ks';document.getElementById('partnerPreview').textContent=new Intl.NumberFormat('en-US').format(20000-fee)+' Ks'}
async function saveCommission(){const rate=Number(document.getElementById('commissionRate').value),button=document.getElementById('saveCommissionButton'),saved=document.getElementById('commissionSaved');if(!Number.isFinite(rate)||rate<0||rate>50)return;button.disabled=true;button.textContent='Saving…';saved.textContent='';try{await api('/admin/api/commission',{method:'POST',body:JSON.stringify({percentage:rate})});saved.textContent=`Saved. New orders will use ${'$'}{rate}%`;noticeView.textContent=`Commission updated to ${'$'}{rate}%`}catch(e){saved.textContent=e.message;button.disabled=false}finally{button.textContent='Save commission rate'}}
if(token)start();
</script></body></html>
""".trimIndent()
