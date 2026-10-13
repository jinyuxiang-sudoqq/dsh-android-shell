package com.dsh.mobile;

/**
 * 手机端样式层：把 DSH 的桌面版网页改造成手机宽度下可用的排版。
 *
 * 为什么单独一个类：
 *   MainActivity 管的是「壳」（窗口、WebView、错误页），这里管的是「填进网页里的东西」
 *   （测量脚本 + 样式补丁），两者的生命周期完全不同，混在一起会让 MainActivity 迅速膨胀。
 *
 * 工作方式分两步，顺序不能颠倒：
 *   1. PROBE —— 只读测量：把页面的真实排版宽度、横向溢出元素、主要容器的计算样式
 *      全部打到 logcat（TAG=DSHProbe）。改样式之前必须先量，否则就是在猜前端的类名和断点。
 *   2. PATCH —— 注入样式：把布局钉到设备宽度、内容列压成两列、
 *      设置抽屉改成全屏纵向堆叠。
 *
 * 两个脚本都是防御式的（全 try/catch + 空值判断）：DSH 前端更新后类名和 DOM 结构都可能变，
 * 最坏情况必须是「样式不生效」，而不是「页面白屏」。
 *
 * ── 这一层踩过的坑，都留在注释里，别再重犯 ──────────────────────────────
 *
 * 1. 纯 CSS 的 !important 压不住站点样式。
 *    设置面板自带 max-width:312px，那条规则的选择器优先级高于单写一个 !important；
 *    即使把 [class] 重复 20 次拉高特异性也不够稳。
 *
 * 2. 但也不要改用「逐节点写内联样式」。
 *    内联确实能赢，可它会被 React 的重渲染冲掉；更糟的是它反过来会盖住
 *    我们写进 style 标签的常驻规则 —— 于是出现「探针量到的是横排、几秒后截图
 *    却是竖排」这种鬼打墙。最后全部改回常驻样式表 + 直接子选择器（`>`），
 *    一次写入长期有效，不跟 React 抢节点。
 *
 * 3. 「最长匹配」这类自作聪明的规则要小心。
 *    类名 GWIfKa_navList 里含有子串 GWIfKa_nav，按数组顺序匹配会先命中容器规则，
 *    改成按字符串长度匹配同样会先命中 GWIfKa_nav（10 个字符 > 7 个字符）。
 *    键名只能写完整前缀，不能靠顺序或长度去猜。
 *
 * 4. querySelectorAll('*') 只返回后代，不含根自身。
 *
 * 5. MutationObserver 监听 style 标签自身的插入会自激循环，必须忽略自己插的标签。
 */
final class MobileSkin {

    private MobileSkin() { }

    /**
     * 只读测量脚本。不改动 DOM，只读几何信息并以 console.log 输出。
     *
     * 输出格式刻意做成一行一条、以 "|" 分隔的纯文本：
     *   logcat 对超长单行会截断，而多行字符串在 logcat 里会被拆散，
     *   所以每条记录独立成行、每行都不长。
     *
     * 另外挂了一个观察器：设置抽屉这类「点开才挂载」的浮层出现时会自动再量一次，
     * 前缀为 OPEN；页面首次加载完成时量一次，前缀为 PROBE。
     */
    static final String PROBE =
        "(function(){"
      + "  function log(){"
      + "    try{ console.log(Array.prototype.join.call(arguments,' ')); }catch(e){}"
      + "  }"
      + "  function txt(el,n){"
      + "    try{"
      + "      var t=(el.textContent||'').replace(/\\s+/g,' ').trim();"
      + "      return t.length>n ? t.slice(0,n)+'\\u2026' : t;"
      + "    }catch(e){ return ''; }"
      + "  }"
      + "  function sv(v){ return (Math.round(v*10)/10); }"
      + "  function path(el){"
      + "    var out=[], n=el, d=0;"
      + "    while(n && n.nodeType===1 && d<6){"
      + "      var s=n.tagName.toLowerCase();"
      + "      if(n.id){ out.unshift(s+'#'+n.id); break; }"
      + "      if(n.className && typeof n.className==='string'){"
      + "        s += '.'+n.className.trim().split(/\\s+/).slice(0,3).join('.');"
      + "      }"
      + "      out.unshift(s); n=n.parentElement; d++;"
      + "    }"
      + "    return out.join('>');"
      + "  }"
      + "  function report(tag){"
      + "  try{"
      + "    var de=document.documentElement, b=document.body;"
      + "    var vw=window.innerWidth, vh=window.innerHeight;"
      + "    log(tag+'|VIEW|innerWidth='+vw+'|innerHeight='+vh"
      + "        +'|dpr='+(window.devicePixelRatio||'?')"
      + "        +'|clientW='+de.clientWidth+'|clientH='+de.clientHeight);"
      + "    log(tag+'|SCROLL|docScrollW='+de.scrollWidth+'|docClientW='+de.clientWidth"
      + "        +'|bodyScrollW='+(b?b.scrollWidth:0)"
      + "        +'|overflowX='+(b?getComputedStyle(b).overflowX:'?'));"
      + "    var m=document.querySelector('meta[name=\"viewport\"]');"
      + "    log(tag+'|META|'+(m?m.getAttribute('content'):'(none)'));"
      + "    var all=document.querySelectorAll('*'), list=[], n=0;"
      + "    for(var i=0;i<all.length;i++){"
      + "      var el=all[i], r=el.getBoundingClientRect();"
      + "      if(r.width<=0 && r.height<=0) continue;"
      + "      if(r.left<0 || r.right>vw+1) list.push([el,r]);"
      + "      n++;"
      + "    }"
      + "    log(tag+'|COUNT|elements='+all.length+'|rendered='+n+'|overflowing='+list.length"
      + "        +'|url='+location.pathname);"
      + "    list.sort(function(a,b){ return b[1].right-a[1].right; });"
      + "    for(var j=0;j<list.length && j<14;j++){"
      + "      var e=list[j][0], q=list[j][1];"
      + "      log(tag+'|OVF'+j+'|'+path(e)+'|w='+sv(q.width)+'|x='+sv(q.left)"
      + "          +'|right='+sv(q.right)+'|ovf='+getComputedStyle(e).overflowX"
      + "          +'|'+txt(e,24));"
      + "    }"
      + "    var ids=['root','app','sidebar','main','content','chat','composer',"
      + "             'settings','panel','editor'];"
      + "    for(var k=0;k<ids.length;k++){"
      + "      var hit=document.getElementById(ids[k]);"
      + "      if(!hit){"
      + "        hit=document.querySelector('[class*=\"'+ids[k]+'\"]')"
      + "            || document.querySelector('[data-testid*=\"'+ids[k]+'\"]');"
      + "      }"
      + "      if(!hit) continue;"
      + "      var hr=hit.getBoundingClientRect(), cs=getComputedStyle(hit);"
      + "      log(tag+'|ID|'+ids[k]+'|w='+sv(hr.width)+'|h='+sv(hr.height)"
      + "          +'|x='+sv(hr.left)+'|display='+cs.display"
      + "          +'|flexDir='+cs.flexDirection+'|maxW='+cs.maxWidth"
      + "          +'|minW='+cs.minWidth+'|pos='+cs.position);"
      + "    }"
      + "    var big=[];"
      + "    for(var z=0;z<all.length;z++){"
      + "      var ez=all[z], rz=ez.getBoundingClientRect();"
      + "      if(rz.width<40 || rz.height<40) continue;"
      + "      big.push(ez);"
      + "    }"
      + "    big.sort(function(a,b){"
      + "      var ra=a.getBoundingClientRect(), rb=b.getBoundingClientRect();"
      + "      return (rb.width*rb.height)-(ra.width*ra.height);"
      + "    });"
      + "    for(var y=0;y<big.length && y<16;y++){"
      + "      var ey=big[y], ry=ey.getBoundingClientRect(), cy=getComputedStyle(ey);"
      + "      log(tag+'|BOX'+y+'|'+path(ey)+'|x='+sv(ry.left)+'|w='+sv(ry.width)"
      + "          +'|h='+sv(ry.height)+'|display='+cy.display"
      + "          +'|flexDir='+cy.flexDirection+'|'+txt(ey,18));"
      + "    }"
      + "    var sc=[];"
      + "    for(var q2=0;q2<all.length;q2++){"
      + "      var eq=all[q2];"
      + "      if((eq.scrollWidth-eq.clientWidth)>2 && eq.clientWidth>0){ sc.push(eq); }"
      + "    }"
      + "    for(var w=0;w<sc.length && w<12;w++){"
      + "      var ew=sc[w], rw=ew.getBoundingClientRect(), cw=getComputedStyle(ew);"
      + "      log(tag+'|SCROLLER'+w+'|'+path(ew)+'|scrollLeft='+sv(ew.scrollLeft)"
      + "          +'|scrollW='+ew.scrollWidth+'|clientW='+ew.clientWidth"
      + "          +'|ovfX='+cw.overflowX+'|x='+sv(rw.left)+'|w='+sv(rw.width));"
      + "    }"
      + "    var probeCls=['GWIfKa_overlay','GWIfKa_panel','GWIfKa_content',"
      + "                 'GWIfKa_options','GWIfKa_header','GWIfKa_nav','GWIfKa_navList',"
      + "                 '_9IQPq_section','mw0_9q_frame'];"
      + "    for(var p1=0;p1<probeCls.length;p1++){"
      + "      var base=probeCls[p1];"
      + "      var found=null;"
      + "      for(var p2=0;p2<all.length;p2++){"
      + "        var cp=all[p2];"
      + "        var cpn=(typeof cp.className==='string'?cp.className:'');"
      + "        if(cpn){"
      + "          var toks=cpn.split(' ');"
      + "          for(var p4=0;p4<toks.length;p4++){"
      + "            var tk=toks[p4];"
      + "            if(tk===base || tk.indexOf(base+'_')===0){ found=cp; break; }"
      + "          }"
      + "        }"
      + "        if(found) break;"
      + "      }"
      + "      if(!found){ log(tag+'|CSS|'+base+'|(not found)'); continue; }"
      + "      var rc=found.getBoundingClientRect(), qq=getComputedStyle(found);"
      + "      log(tag+'|CSS|'+base+'|w='+sv(rc.width)+'|x='+sv(rc.left)"
      + "          +'|display='+qq.display+'|flexDir='+qq.flexDirection"
      + "          +'|minW='+qq.minWidth+'|maxW='+qq.maxWidth"
      + "          +'|gridCols='+qq.gridTemplateColumns+'|ovfX='+qq.overflowX);"
      + "      var kids=found.children, kd='';"
      + "      for(var p3=0;p3<kids.length && p3<5;p3++){"
      + "        var kk=kids[p3], kr=kk.getBoundingClientRect();"
      + "        var kn=(typeof kk.className==='string'?kk.className:'').split(/\\s+/)[0]"
      + "                 || kk.tagName;"
      + "        kd += kn+'(w='+sv(kr.width)+',x='+sv(kr.left)+') ';"
      + "      }"
      + "      log(tag+'|CSSKIDS|'+base+'|'+kids.length+'|'+kd);"
      + "    }"
      + "    var frame=document.querySelector('[class*=frame]');"
      + "    if(frame){"
      + "      var fcs=getComputedStyle(frame), fr=frame.getBoundingClientRect();"
      + "      log(tag+'|FRAME|w='+sv(fr.width)+'|h='+sv(fr.height)"
      + "          +'|rows='+fcs.gridTemplateRows+'|cols='+fcs.gridTemplateColumns);"
      + "      var fc=frame.children;"
      + "      for(var fi=0;fi<fc.length && fi<6;fi++){"
      + "        var fe=fc[fi], fer=fe.getBoundingClientRect();"
      + "        var fen=(typeof fe.className==='string'?fe.className:'').split(/\\s+/)[0]"
      + "                 || fe.tagName;"
      + "        log(tag+'|FRAMEKID'+fi+'|'+fen+'|x='+sv(fer.left)+'|y='+sv(fer.top)"
      + "            +'|w='+sv(fer.width)+'|h='+sv(fer.height)+'|'+txt(fe,14));"
      + "      }"
      + "    }"
      + "    var hero=document.querySelector('[class*=composerHero],[class*=composerStack]');"
      + "    if(hero){"
      + "      var hh=hero.getBoundingClientRect();"
      + "      log(tag+'|HERO|y='+sv(hh.top)+'|h='+sv(hh.height)+'|bottom='+sv(hh.bottom));"
      + "    }"
      + "    var navList=document.querySelector('[class*=navList]');"
      + "    if(navList){"
      + "      var nl=navList.getBoundingClientRect(), ncs=getComputedStyle(navList);"
      + "      log(tag+'|NAV|navList|w='+sv(nl.width)+'|h='+sv(nl.height)"
      + "          +'|display='+ncs.display+'|flow='+ncs.gridAutoFlow"
      + "          +'|cols='+ncs.gridTemplateColumns+'|flexDir='+ncs.flexDirection);"
      + "      var nk=navList.children;"
      + "      for(var ni=0;ni<nk.length && ni<8;ni++){"
      + "        var ne=nk[ni], nr=ne.getBoundingClientRect();"
      + "        var ncc=getComputedStyle(ne);"
      + "        log(tag+'|NAVITEM'+ni+'|w='+sv(nr.width)+'|x='+sv(nr.left)"
      + "            +'|y='+sv(nr.top)+'|h='+sv(nr.height)"
      + "            +'|display='+ncc.display+'|flexDir='+ncc.flexDirection"
      + "            +'|'+txt(ne,16));"
      + "      }"
      + "    }"
      + "    var sideCol=document.querySelector('[class*=sidebarCol]');"
      + "    if(sideCol){"
      + "      var sr=sideCol.getBoundingClientRect();"
      + "      log(tag+'|SIDE|w='+sv(sr.width)+'|x='+sv(sr.left)+'|h='+sv(sr.height));"
      + "    }"
      + "    log(tag+'-DONE');"
      + "  }catch(e){ log(tag+'-ERROR|'+(e&&e.message)); }"
      + "  }"
      + "  try{"
      + "    report('PROBE');"
      + "    if(window.__dshProbeObserver) return;"
      + "    var fired=false, maxArea=0;"
      + "    var obs=new MutationObserver(function(){"
      + "      try{"
      + "        if(fired) return;"
      + "        var cand=document.querySelectorAll("
      + "          '[class*=setting],[class*=Setting],[class*=drawer],[class*=Drawer],"
      + "           [class*=dialog],[class*=Dialog],[class*=modal],[class*=Modal],"
      + "           [class*=overlay],[class*=Overlay],[class*=sheet],[class*=Sheet]');"
      + "        var best=0;"
      + "        for(var i=0;i<cand.length;i++){"
      + "          var r=cand[i].getBoundingClientRect();"
      + "          var a=r.width*r.height;"
      + "          if(r.width>200 && a>best) best=a;"
      + "        }"
      + "        if(best>maxArea) maxArea=best;"
      + "        if(maxArea>120000){"
      + "          fired=true;"
      + "          clearTimeout(window.__dshProbeTimer);"
      + "          window.__dshProbeTimer=setTimeout(function(){"
      + "            try{ obs.disconnect(); }catch(e){}"
      + "            report('OPEN');"
      + "          }, 1200);"
      + "        }"
      + "      }catch(e){}"
      + "    });"
      + "    window.__dshProbeObserver=obs;"
      + "    obs.observe(document.documentElement,"
      + "      {childList:true, subtree:true, attributes:true,"
      + "       attributeFilter:['class','style']});"
      + "  }catch(e){ log('PROBE-OBS-ERROR|'+(e&&e.message)); }"
      + "})()";

    /**
     * 输入框（composer）的手机端微调，作为一条独立 CSS 片段注入。
     *
     * 为什么单独拎出来：这段选择器很长，之前直接内联进脚本拼接里，
     * 一连串的引号嵌套把 Java 字符串截断过两次（javac 报「未终止的字符字面量」）。
     * 单独成一个常量之后，内容原样可见，不再需要数引号。
     *
     * 改的是两处实测出来的毛病：
     *   1. 正文区只有 52px 高（_1i9npq_input / _grow / _scroll），太矮；
     *   2. 工具行 _1i9npq_row 用 space-between，左边 _1i9npq_tools 占掉 80px，
     *      只剩 52px 给模型选择器，于是「DeepSeek-V4」被截成「DeepSeek-V4....」。
     *      把工具行最后一个子项设成 flex:1，让它吃掉余量。
     *
     * 选择器一律带 composerStack 前缀 —— 踩过的坑：[class*=_row] 这种裸后缀
     * 会连首页的 heroWorkspaceRow 一起命中，把刚修好的「标准模式」又压回「标...」。
     */
    private static final String JS_COMPOSER_CSS =
        "'[class*=composerStack] [class*=composer] [class*=_row],"
      + "[class*=composerStack] [class*=composer] [class*=toolbar],"
      + "[class*=composerStack] [class*=composer] [class*=actionRow]{"
      + "gap:10px !important;align-items:center !important;}'"
      + " + '[class*=composerStack] [class*=composer] [class*=_tools]{"
      + "flex:0 0 auto !important;}'"
      + " + '[class*=composerStack] [class*=composer] [class*=_row] > *:last-child{"
      + "flex:1 1 auto !important;min-width:0 !important;max-width:100% !important;}'"
      + " + '[class*=composerStack] [class*=composer] [class*=menuAnchor],"
      + "[class*=composerStack] [class*=composer] [class*=seatLabel]{"
      + "min-width:0 !important;max-width:100% !important;}'"
      + " + '[class*=composerStack] [class*=composer] [class*=_input],"
      + "[class*=composerStack] [class*=composer] [class*=_grow],"
      + "[class*=composerStack] [class*=composer] [class*=_scroll]{"
      + "min-height:76px !important;}'";

    /**
     * 键盘遮挡修复。
     *
     * 根因（真机实测）：Android 15+ 忽略了 menu 里的
     * android:windowSoftInputMode="adjustResize" —— 系统强制 edge-to-edge 之后，
     * 软键盘弹出时**布局视口不再被压缩**（window.innerHeight 恒定 792），
     * 只有 visualViewport 变小。而输入框是钉在视口底部的，于是键盘直接盖在它上面。
     *
     * 做法：把根容器的高度明确写成 visualViewport 的高度（px，不能用 100vh/100%，
     * 那样等于没缩），并把键盘高度记到 --dsh-kb 供样式引用。
     *
     * 关键细节，缺一不可：
     *   · 高度必须用 px 写死。视口被压缩时布局才会跟着缩，否则内容仍按 792 排布，
     *     底部元素照样被盖住 —— 这正是「adjustResize 失效」的直接后果。
     *   · 差值要设下限（80px）。地址栏收起之类的几像素变化不该当成键盘。
     *   · 键盘弹出时浏览器会给可视视口加一个 offsetTop，必须把窗口滚回 0，
     *     否则内容整体上移一段、下面露出空白。
     *   · 只在值真的变化时才写 DOM，避免 resize 风暴里反复触发重排。
     *
     * 用 visualViewport 而不是监听键盘事件：这个 API 在 WebView 里可靠，
     * 而且不依赖任何 DSH 的类名，前端换版本也失效不了。
     */
    static final String KEYBOARD =
        "(function(){"
      + "  if(window.__dshKeyboardFix){ try{window.__dshKbApply();}catch(e){} return 'already'; }"
      + "  var KB_MIN=80, lastKb=-1;"
      + "  /*"
      + "   * 底部让位的最小值。手势条本身只有几像素，光按 insets 让位不够 ——"
      + "   * 输入框会紧贴手势条、拇指容易误触，观感上也像被切掉一截。"
      + "   */"
      + "  var BOTTOM_MIN=20;"
      + "  function setSafeBottom(){"
      + "    try{"
      + "      var v=parseFloat(getComputedStyle(document.documentElement)"
      + "        .getPropertyValue('--dsh-safe-bottom'));"
      + "      if(isNaN(v)) v=0;"
      + "      var total=Math.max(BOTTOM_MIN, Math.round(v));"
      + "      document.documentElement.style.setProperty('--dsh-bottom', total+'px');"
      + "      return total;"
      + "    }catch(e){ return BOTTOM_MIN; }"
      + "  }"
      + "  function apply(){"
      + "    try{"
      + "      var vv=window.visualViewport; if(!vv) return;"
      + "      var safe=setSafeBottom();"
      + "      /*"
      + "       * 可视视口高度里仍含底部导航栏那一段（状态栏/导航栏由系统绘制，"
      + "       * 不占网页的布局视口）。根容器高度要减掉它，否则底部内容"
      + "       * ——输入框就是 sticky 在底部的——会被导航栏压住一截。"
      + "       */"
      + "      var usable=Math.round(vv.height)-safe;"
      + "      var kb=Math.round(window.innerHeight-vv.height-vv.offsetTop);"
      + "      if(kb<KB_MIN) kb=0;"
      + "      var key=kb+'|'+usable;"
      + "      if(key===lastKb) return;"
      + "      lastKb=key;"
      + "      document.documentElement.style.setProperty('--dsh-kb', kb+'px');"
      + "      var el=document.getElementById('root')||document.body;"
      + "      if(kb>0){"
      + "        el.style.setProperty('height', usable+'px', 'important');"
      + "        el.style.setProperty('max-height', usable+'px', 'important');"
      + "      }else{"
      + "        /*"
      + "         * 键盘收起时不写死高度：页面本身可能比视口高（长对话），"
      + "         * 写死会把内容截断。底部让位交给常驻 CSS 规则。"
      + "         */"
      + "        el.style.removeProperty('height');"
      + "        el.style.removeProperty('max-height');"
      + "      }"
      + "      if(kb>0) stickToBottom();"
      + "      if(vv.offsetTop>0) window.scrollTo(0,0);"
      + "      var ae=document.activeElement;"
      + "      if(kb>0 && ae && (ae.tagName==='TEXTAREA'||ae.tagName==='INPUT'||ae.isContentEditable)){"
      + "        var r=ae.getBoundingClientRect();"
      + "        if(r.bottom>vv.height){"
      + "          try{ ae.scrollIntoView({block:'center',behavior:'smooth'}); }"
      + "          catch(e2){ ae.scrollIntoView(); }"
      + "        }"
      + "      }"
      + "    }catch(e){}"
      + "  }"
      + "  /*"
      + "   * 把对话滚到最新一条。"
      + "   *"
      + "   * 为什么要这个：只做「输入框不被挡住」是不够的 —— 键盘弹起会占掉屏幕"
      + "   * 下半部分，而消息列表的滚动位置没变，于是**用户看到的还是原来那段，"
      + "   * 最新一条恰好落在键盘后面**。移动端 IM（含 DeepSeek App）的做法都是"
      + "   * 弹键盘时把列表贴到底。"
      + "   *"
      + "   * 实现要点："
      + "   *   · 直接改 scrollTop 而不是 scrollIntoView —— 后者会连带滚动祖先容器，"
      + "   *     可能把整个界面推歪；我们要动的只是消息列表这一个滚动盒。"
      + "   *   · 用 rAF 延迟一帧：键盘补偿刚改完根容器高度，浏览器还没重排，"
      + "   *     此刻读到的 scrollHeight 是旧值。"
      + "   *   · 找不到容器就静默跳过（前端换结构时最坏是「不滚」，不是报错）。"
      + "   */"
      + "  function stickToBottom(){"
      + "    try{"
      + "      requestAnimationFrame(function(){"
      + "        try{"
      + "          var box=document.querySelector('[class*=scrollBody]');"
      + "          if(!box) return;"
      + "          if(box.scrollHeight > box.clientHeight + 2) box.scrollTop = box.scrollHeight;"
      + "        }catch(e){}"
      + "      });"
      + "    }catch(e){}"
      + "  }"
      + "  window.__dshKeyboardFix=true; window.__dshKbApply=apply;"
      + "  /* 一并暴露贴底函数：键盘无法在开发机上模拟，"
      + "     把它挂出来才能用「塞长内容 + 滚到顶 + 调用它」的方式单独验证。 */"
      + "  window.__dshKbStick=stickToBottom;"
      + "  if(window.visualViewport){"
      + "    visualViewport.addEventListener('resize',apply);"
      + "    visualViewport.addEventListener('scroll',apply);"
      + "  }"
      + "  window.addEventListener('resize',apply);"
      + "  window.addEventListener('orientationchange',function(){ setTimeout(apply,300); });"
      + "  document.addEventListener('focusin',function(){ setTimeout(apply,50); setTimeout(apply,300); });"
      + "  document.addEventListener('focusout',function(){"
      + "    setTimeout(apply,50); setTimeout(apply,300);"
      + "    if(stickTimer){ clearInterval(stickTimer); stickTimer=null; }"
      + "  });"
      + "  /*"
      + "   * 打字期间持续贴底。"
      + "   *"
      + "   * 输入框是多行自增高的：每多一行，消息列表高度就少一点，"
      + "   * 不跟着调的话「最新一条」会慢慢被挤到键盘后面。"
      + "   * 用轻量轮询而不是监听 input 事件——DSH 的输入框是 contenteditable，"
      + "   * 事件名与冒泡行为不稳定，而 600ms 一次的 scrollTop 赋值开销可以忽略；"
      + "   * 只在输入框聚焦期间跑（focusout 里清掉）。"
      + "   */"
      + "  var stickTimer=null;"
      + "  document.addEventListener('focusin',function(){"
      + "    if(stickTimer) return;"
      + "    stickTimer=setInterval(function(){"
      + "      try{ if(lastKb>0) stickToBottom(); }catch(e){}"
      + "    },600);"
      + "  });"
      + "  apply();"
      + "  return 'kb-installed';"
      + "})()";

    /**
     * 打开 DSH 自己的设置浮层（供外壳设置页里的「DSH 设置」入口调用）。
     *
     * 背景：侧栏底部原本有两个按钮 —— 外壳设置（手机图标）与 DSH 自己的设置（齿轮），
     * 而它们在 rail 布局里处在同一个 flex 行、只有 36px 宽，**两个按钮完全重叠**。
     * 与其挤在一起，不如合成一个入口：手机图标打开外壳设置页，那一页再给出去
     * DSH 设置的入口。
     *
     * 为此齿轮按钮被 CSS 隐藏了（见 SETTINGS_ENTRY 注入的样式）。
     * 所以这里要先临时「亮出来」再点它 —— 直接 click() 对 display:none 的元素无效。
     *
     * 用真实点击而不是自己去改动 DSH 的状态：设置浮层的开关状态归 DSH 自己管，
     * 我们伪造状态迟早会跟它对不上。点它自己的按钮是最不容易失效的做法。
     */
    static final String OPEN_WEB_SETTINGS =
        "(function(){"
      + "  try{"
      + "    var bs=document.querySelectorAll('button[aria-label]');"
      + "    for(var i=0;i<bs.length;i++){"
      + "      var l=bs[i].getAttribute('aria-label')||'';"
      + "      if(l!=='设置' && !/^settings$/i.test(l)) continue;"
      + "      var prev=bs[i].style.display;"
      + "      bs[i].style.setProperty('display','flex','important');"
      + "      bs[i].click();"
      + "      bs[i].style.display=prev;"
      + "      return 'opened';"
      + "    }"
      + "    return 'none';"
      + "  }catch(e){ return 'error'; }"
      + "})()";

    /**
     * 侧栏底部的「手机端设置」入口。
     *
     * 为什么要有它：开机动画的开关原先只能靠长按调出来，而长按挂在**整个界面**上，
     * 用户在任何一个页面按住屏幕都会弹设置 —— 太吵。现在开关收进外壳自己的设置页
     * （MobileSettingsActivity），这里只负责给它一个固定、看得见的入口。
     *
     * 为什么插在 DSH 自己的「设置」按钮上方、同一个 footer 容器里：
     *   · 用 fixed 悬浮在角落会和页面自己的交互打架（也正是不想再做的事）；
     *   · 塞进 DSH 的 footer 就等于复用它的布局与滚动行为，位置天然正确；
     *   · 放在它上面而不是下面，是为了不把 DSH 原生的设置按钮挤离拇指习惯的位置。
     *
     * 为什么按钮用「手机」图标而不是齿轮：齿轮会让用户以为这是 DSH 的设置，
     * 而点开却是外壳的设置页，反而困惑。手机图标表达「这是设备层面的设置」。
     *
     * 为什么只做幂等插入、不自己维护存在性：DSH 重渲染会把它抹掉，
     * 所以用一个轻量定时器补插 —— 比自己挂 MutationObserver 更省事，
     * 而且补插的判定（按 id 查）本身就是幂等的。
     */
    static final String SETTINGS_ENTRY =
        "(function(){"
      + "  if(window.__dshSettingsEntry) return 'already';"
      + "  var ID='dsh-shell-settings';"
      + "  function log(m){ try{ console.log(m); }catch(e){} }"
      + "  function findSeat(){"
      + "    /*"
      + "     * 目标是 DSH 侧栏底部那个「设置按钮所在的行」"
      + "     * （GWIfKa_triggerRow / railRow）。它本来就只有 DSH 一个齿轮按钮，"
      + "     * 我们把自己的按钮插进去、改成一列两格。"
      + "     *"
      + "     * 踩过的坑：第一版按 aria-label='设置' 找到齿轮、插到它的父节点里 ——"
      + "     * 那个父节点是个 display:contents 的包装（不参与布局），"
      + "     * 结果我们的按钮和齿轮一起被后面的 display:none 隐藏掉了，"
      + "     * 整个界面**一个设置入口都不剩**。"
      + "     * 现在直接认行容器本身，不再依赖齿轮的位置。"
      + "     */"
      + "    var row=document.querySelector('[class*=triggerRow]');"
      + "    if(row) return { seat:row, before:row.firstChild };"
      + "    return null;"
      + "  }"
      + "  function build(){"
      + "    var b=document.createElement('button');"
      + "    b.id=ID; b.type='button';"
      + "    b.setAttribute('aria-label','手机端设置');"
      + "    b.title='手机端设置';"
      + "    /*"
      + "     * 只放图标、不放文字。"
      + "     *"
      + "     * 踩过的坑：第一版带了「手机端设置」文字，而侧栏收起时只有 46px 宽 ——"
      + "     * 五个字竖着一列排下来，按钮被撑到 100px 高，看起来像坏掉了。"
      + "     * 侧栏里 DSH 自己的按钮全是纯图标，文字只出现在 title/aria-label 里。"
      + "     *"
      + "     * 尺寸按 DSH 的图标按钮来：36px 见方（10px 内边距 + 36 = 侧栏 56px 刚好放得下）。"
      + "     */"
      + "    b.style.cssText='display:flex;align-items:center;justify-content:center;"
      + "width:36px;height:36px;flex:0 0 auto;box-sizing:border-box;"
      + "background:transparent;border:0;padding:0;margin:0;border-radius:8px;"
      + "cursor:pointer;"
      + "color:var(--dsw-alias-text-secondary,#8a8a93);';"
      + "    b.innerHTML='<svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\""
      + " stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\""
      + " stroke-linejoin=\"round\" aria-hidden=\"true\">"
      + "<rect x=\"7\" y=\"2\" width=\"10\" height=\"20\" rx=\"2\"/>"
      + "<line x1=\"10.5\" y1=\"18.5\" x2=\"13.5\" y2=\"18.5\"/></svg>';"
      + "    b.addEventListener('click',function(ev){"
      + "      ev.preventDefault(); ev.stopPropagation();"
      + "      try{"
      + "        if(window.DSHShell && window.DSHShell.openMobileSettings){"
      + "          window.DSHShell.openMobileSettings();"
      + "        }else{ log('SHELLSETTINGS|no bridge'); }"
      + "      }catch(e){ log('SHELLSETTINGS|'+e.message); }"
      + "    },true);"
      + "    return b;"
      + "  }"
      + "  function ensure(){"
      + "    try{"
      + "      /*"
      + "       * 把设置行改成「一列两格」：我们的入口在上，DSH 的齿轮在下。"
      + "       *"
      + "       * 为什么必须改布局：这一行在 rail 里只有 36px 宽（w=36、h=36，"
      + "       * 上下还各有 8px/10px 外边距），两个 36px 的按钮横着放不下，"
      + "       * 挤在一起就会**完全重叠**在同一个位置（实测两个按钮 t 都是 740）——"
      + "       * 这正是用户报的问题。"
      + "       *"
      + "       * 改成纵向排列后总高 72px，而这一行锚在侧栏底部（b=792）、"
      + "       * 上面从「搜索会话」(b=298) 到设置区有 400+px 空闲，"
      + "       * footer 又是 column 布局，所以向上生长不会挤压别的东西。"
      + "       */"
      + "      if(!document.getElementById('dsh-shell-settings-style')){"
      + "        var st=document.createElement('style');"
      + "        st.id='dsh-shell-settings-style';"
      + "        st.textContent="
      + "          '[class*=triggerRow]{flex-direction:column !important;"
      + "height:auto !important;min-height:0 !important;gap:4px !important;}'"
      + "        + '[class*=triggerRow] > *{flex:0 0 auto !important;}';"
      + "        (document.head||document.documentElement).appendChild(st);"
      + "      }"
      + "      if(document.getElementById(ID)) return;"
      + "      var hit=findSeat();"
      + "      if(!hit) return;"
      + "      hit.seat.insertBefore(build(), hit.before);"
      + "      log('SHELLSETTINGS|inserted');"
      + "    }catch(e){ log('SHELLSETTINGS|err '+(e&&e.message)); }"
      + "  }"
      + "  window.__dshSettingsEntry=true;"
      + "  ensure();"
      + "  setInterval(ensure, 1500);"
      + "  return 'settings-entry-installed';"
      + "})()";

    /**
     * 收起侧栏抽屉（供返回键调用）。
     *
     * 为什么不模拟一次坐标点击：坐标要自己算（轨道宽度、状态栏高度都会变），
     * 而 DSH 的展开态里那个按钮就在侧栏内部，位置随布局浮动 —— 迟早点空。
     * 这里按 aria-label 找到那个开关直接 click()，位置无关。
     *
     * aria-label 是 DSH 自己给的（收起/折叠 两种文案都可能出现），
     * 所以用宽松匹配；找不到就退回派发 Escape，最后再报 none 让调用方走下一步。
     *
     * 返回值：'closed' / 'none'（供日志判定，不依赖它做流程控制）。
     */
    static final String CLOSE_NAV =
        "(function(){"
      + "  try{"
      + "    if(!window.__dshNavOpen) return 'none';"
      + "    var bs=document.querySelectorAll('button[aria-label]');"
      + "    /*"
      + "     * 匹配必须收紧。"
      + "     *"
      + "     * 踩过的坑：第一版用 /收起|折叠|关闭侧|collapse/i 宽匹配，"
      + "     * 结果同时命中三个按钮 ——「收起侧边栏」「收起右侧边栏」「收起」。"
      + "     * 当时只是**碰巧**选中了正确的那个（querySelectorAll 的顺序里它排第一），"
      + "     * 换个版本、多一个面板就会关错东西。"
      + "     *"
      + "     * 现在要求「侧边栏」且**排除「右侧」** —— 左边栏是我们这个抽屉，"
      + "     * 右边栏是另一回事，不能碰。"
      + "     */"
      + "    for(var i=0;i<bs.length;i++){"
      + "      var l=bs[i].getAttribute('aria-label')||'';"
      + "      if(l.indexOf('右侧')>=0) continue;"
      + "      if(l.indexOf('侧边栏')<0 && l.indexOf('侧栏')<0) continue;"
      + "      if(!/收起|折叠|关闭|collapse|close/i.test(l)) continue;"
      + "      bs[i].click(); return 'closed';"
      + "    }"
      + "    var opts={key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true};"
      + "    document.dispatchEvent(new KeyboardEvent('keydown',opts));"
      + "    return 'esc';"
      + "  }catch(e){ return 'error'; }"
      + "})()";

    /**
     * 侧边栏：展开时改成覆盖式抽屉。
     *
     * 之前的问题（这条注释是为了别再犯）：PATCH 里写过
     *   :root{--dsh-rail:46px} + [class*=sidebarCol]{max-width:46px}
     * 想把轨道从 56px 收到 46px。但 DSH 展开侧栏时会往 frame 写行内样式
     *   grid-template-columns: 280px minmax(0px,1fr) minmax(0px,0px)
     * max-width:46px 把第一列按死，「展开」在视觉上完全没反应 ——
     * 侧栏永远 46px、正文永远 314px，会话列表根本看不到。
     *
     * 现在不再跟 DSH 抢尺寸：轨道宽窄与栅格完全由它自己算（56px ↔ 280px），
     * 这里只在它**展开**时把侧栏改成 fixed 覆盖层，这样正文不再被挤压。
     *
     * 展开/收起怎么判断：读 frame 的行内栅格第一列宽度，>=100px 即展开。
     * 阈值取 100 而不是「56+一点」，是为了远离两端、不会卡在边界上误判。
     *
     * 为什么用 html[data-dsh-nav] 而不是类名：DSH 的类名是 CSS Module 哈希过的，
     * 换版本就变；属性名是我们自己写的，稳定。
     *
     * 为什么要 MutationObserver：展开/收起是 DSH 改自己的行内样式实现的。
     * 监听它比我们自己维护一个开关状态可靠 —— 后者迟早会跟真实状态不一致
     * （点外部关闭、快捷键、程序化切换都会绕过我们）。
     *
     * ── 这个常量只能由字符串拼接构成 ───────────────────────────────────
     *
     * 踩过的大坑：曾在常量中间插入 Java 注释（`/* ... *\/`）来说明 CSS。
     * 那些注释没被包进 `+ "..."`，于是它们的文字**根本没进 JS**；而 Java 编译器
     * 只检查 Java 语法，**构建照样通过**。结果整个 IIFE 在运行时解析失败 ——
     * 表现为「侧栏脚本从未执行」：window.__dshSidebarFix 为 undefined、
     * html 上没有 data-dsh-nav、样式标签不存在、侧栏仍是 56px 静态占位。
     * 界面上只看到「布局没变」，极易误判成 CSS 写错，排查了很久。
     *
     * 所以要写说明：写在**这个 Java 注释块**里，或者写成 JS 注释放进字符串内。
     * 每改完请跑 ../default-workspace 下的 check-mobileskin-js.mjs 做语法检查 ——
     * 它专门用来在构建期抓出这类「javac 看不出、运行时才炸」的错误。
     */
    static final String SIDEBAR =
        "(function(){"
      + "if(window.__dshSidebarFix){return 'already';}"
      + "var STYLE_ID='dsh-mobile-sidebar';"
      + "var OPEN_MIN=100;"
      + "var CSS='html[data-dsh-nav=\"rail\"] [class*=sidebarCol]{position:fixed !important;left:0 !important;top:52px !important;bottom:auto !important;width:56px !important;min-width:56px !important;max-width:56px !important;height:auto !important;padding-bottom:10px !important;z-index:55 !important;border-radius:0 0 14px 0;background:transparent !important;pointer-events:none !important;}'"
      + "+ 'html[data-dsh-nav=\"rail\"] [class*=sidebarCol] *:has([aria-label=\"打开侧边栏\"]){display:block !important;}'"
      + "+ 'html[data-dsh-nav=\"rail\"] [class*=sidebarCol] [class*=logoRow]:has([aria-label=\"打开侧边栏\"]){display:flex !important;padding-top:0 !important;}'"
      + "+ 'html[data-dsh-nav=\"rail\"] [class*=sidebarCol] button[aria-label=\"打开侧边栏\"]{display:flex !important;pointer-events:auto !important;}'"
      + "+ 'html[data-dsh-nav=\"rail\"] [class*=sidebarCol] *:has([aria-label=\"打开侧边栏\"]) > *:not(:has([aria-label=\"打开侧边栏\"])):not([aria-label=\"打开侧边栏\"]){display:none !important;}'"
      + "+ 'html[data-dsh-nav=\"rail\"] button[aria-label=\"打开侧边栏\"]{width:36px !important;height:36px !important;border-radius:50% !important;background:color-mix(in srgb, currentColor 10%, transparent) !important;box-shadow:0 2px 8px rgba(0,0,0,.35);}'"
      + "+ 'html[data-dsh-nav=\"rail\"] [class*=frame]{grid-template-columns:0 minmax(0,1fr) !important;}html[data-dsh-nav=\"rail\"] [class*=centerCol]{width:100vw !important;max-width:100vw !important;}'"
      + "+ 'html[data-dsh-nav=\"open\"] [class*=sidebarCol]{position:fixed !important;left:0 !important;top:0 !important;bottom:0 !important;width:min(86vw,340px) !important;min-width:min(86vw,340px) !important;max-width:min(86vw,340px) !important;z-index:60 !important;padding-top:var(--dsh-safe-top,30px) !important;padding-bottom:var(--dsh-safe-bottom,0px) !important;box-shadow:0 0 0 100vmax rgba(0,0,0,.5);}html[data-dsh-nav=\"open\"] [class*=centerCol]{width:100vw !important;max-width:100vw !important;}';"
      + "function frameEl(){return document.querySelector('[class*=frame]');}"
      + "function ensureStyle(){"
      + "var el=document.getElementById(STYLE_ID);"
      + "if(el){if(el.textContent!==CSS){el.textContent=CSS;}return;}"
      + "el=document.createElement('style');"
      + "el.id=STYLE_ID;"
      + "el.textContent=CSS;"
      + "(document.head||document.documentElement).appendChild(el);"
      + "}"
      + "function wantState(){"
      + "var f=frameEl();"
      + "if(!f){return null;}"
      + "var inline=f.style.gridTemplateColumns||\"\";"
      + "var m=inline.match(/^[0-9.]+/);"
      + "if(!m){return \"rail\";}"
      + "return parseFloat(m[0])>=OPEN_MIN?\"open\":\"rail\";"
      + "}"
      + "var BACKDROP_ID='dsh-nav-backdrop';"
      + "function closeNav(){"
      + "var bs=document.querySelectorAll('button[aria-label]');"
      + "for(var i=0;i<bs.length;i++){"
      + "var l=bs[i].getAttribute('aria-label')||'';"
      + "if(l.indexOf('右侧')>=0){continue;}"
      + "if(l.indexOf('侧边栏')<0&&l.indexOf('侧栏')<0){continue;}"
      + "if(!/收起|折叠|关闭/.test(l)){continue;}"
      + "bs[i].click();"
      + "return true;"
      + "}"
      + "var o={key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true};"
      + "document.dispatchEvent(new KeyboardEvent('keydown',o));"
      + "return false;"
      + "}"
      + "function syncBackdrop(open){"
      + "var el=document.getElementById(BACKDROP_ID);"
      + "if(open){"
      + "if(el){return;}"
      + "el=document.createElement('div');"
      + "el.id=BACKDROP_ID;"
      + "el.setAttribute('aria-hidden','true');"
      + "el.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;z-index:50;background:rgba(0,0,0,0.28);';"
      + "el.addEventListener('click',function(ev){ev.preventDefault();ev.stopPropagation();closeNav();},true);"
      + "(document.body||document.documentElement).appendChild(el);"
      + "}else if(el&&el.parentNode){"
      + "el.parentNode.removeChild(el);"
      + "}"
      + "}"
      + "function sync(){"
      + "var want=wantState();"
      + "if(!want){return false;}"
      + "ensureStyle();"
      + "var h=document.documentElement;"
      + "if(h.getAttribute(\"data-dsh-nav\")!==want){h.setAttribute(\"data-dsh-nav\",want);}"
      + "window.__dshNavOpen=(want===\"open\");"
      + "syncBackdrop(want===\"open\");"
      + "return true;"
      + "}"
      + "window.__dshNavSync=sync;"
      + "function attach(){"
      + "try{"
      + "var f=frameEl();"
      + "if(!f){return false;}"
      + "if(window.__dshNavObserver){window.__dshNavObserver.disconnect();}"
      + "var obs=new MutationObserver(function(){sync();});"
      + "obs.observe(f,{attributes:true,attributeFilter:[\"style\"]});"
      + "window.__dshNavObserver=obs;"
      + "return true;"
      + "}catch(e){return false;}"
      + "}"
      + "window.__dshSidebarFix=true;"
      + "document.addEventListener(\"click\",function(){setTimeout(sync,0);setTimeout(sync,150);},true);"
      + "var tries=0;"
      + "function boot(){"
      + "tries++;"
      + "var ok=sync();"
      + "if(ok){attach();return;}"
      + "if(tries<50){setTimeout(boot,300);}"
      + "}"
      + "boot();"
      + "return 'nav-installed';"
      + "})()";

    /**
     * 浮层状态探测 + 关闭。
     *
     * 存在理由（真机 bug）：DSH 的设置面板是一个网页浮层，**不进浏览器历史**。
     * 而安卓返回键原先走的是「能后退就后退，否则退出 Activity」，于是打开设置后
     * 按返回 = 直接回到桌面，浮层没有任何出口。
     *
     * 这里做两件事：
     *   · REPORT  —— 常驻监测浮层是否开着，把结果写到 window.__dshOverlayOpen，
     *                供安卓侧**同步**读取（evaluateJavascript 是异步的，返回键
     *                等不起，所以状态必须事先算好）。
     *   · DISMISS —— 关掉浮层。用 Esc：DSH 的浮层都认它（动画文档、设置面板
     *                都是 keydown Escape 关闭），比自己去找关闭按钮更可靠。
     *
     * 判定用「fixed 定位 + 尺寸够大 + 不是页面根节点」，不看具体类名 ——
     * 这样 DSH 前端换版本、浮层类名改了，这里也不会失效。
     */
    static final String OVERLAY =
        "(function(){"
      + "  function log(m){ try{ console.log(m); }catch(e){} }"
      + "  function hasOverlay(){"
      + "    try{"
      + "      var all=document.querySelectorAll('[class]');"
      + "      for(var i=0;i<all.length;i++){"
      + "        var el=all[i];"
      + "        if(el.id==='root' || el.id==='app') continue;"
      + "        var r=el.getBoundingClientRect();"
      + "        if(r.width<200 || r.height<200) continue;"
      + "        var cs=getComputedStyle(el);"
      + "        /*"
      + "         * 只认「覆盖层」：fixed 定位、且真的盖住了视口。"
      + "         * 不要求它是 body 的直接子节点 —— 第一版要求了，结果 DSH 的"
      + "         * 浮层挂在哪一层不确定时就漏判，返回键又退回了桌面。"
      + "         * 也不看具体类名，前端换版本失效不了。"
      + "         */"
      + "        if(cs.position!=='fixed') continue;"
      + "        if(cs.display==='none' || cs.visibility==='hidden') continue;"
      + "        if(parseFloat(cs.opacity||'1')<0.05) continue;"
      + "        if(cs.pointerEvents==='none') continue;"
      + "        if(r.width < window.innerWidth*0.5) continue;"
      + "        if(r.height < window.innerHeight*0.5) continue;"
      + "        return true;"
      + "      }"
      + "    }catch(e){}"
      + "    return false;"
      + "  }"
      + "  try{"
      + "    window.__dshOverlayOpen = hasOverlay();"
      + "    if(!window.__dshOverlayObserver){"
      + "      var obs=new MutationObserver(function(){"
      + "        window.__dshOverlayOpen = hasOverlay();"
      + "      });"
      + "      window.__dshOverlayObserver=obs;"
      + "      obs.observe(document.documentElement,"
      + "        {childList:true, subtree:true, attributes:true,"
      + "         attributeFilter:['class','style','hidden']});"
      + "    }"
      + "    /*"
      + "     * 这里不需要像 SIDEBAR 那样加「等 DOM 出现」的重试。"
      + "     *"
      + "     * 原因：观察目标固定是 document.documentElement，而它在脚本注入时必然"
      + "     * 已经存在（脚本本身就挂在 <head>/<body> 上跑）。SIDEBAR 会失效是因为它"
      + "     * 要 querySelector('[class*=frame]') —— 那个节点由 SPA 稍后渲染。"
      + "     * 差别在「目标是否已经存在」，不是「脚本有没有跑」。"
      + "     */"
      + "  }catch(e){ log('OVERLAY-ERROR|'+(e&&e.message)); }"
      + "})()";

    /**
     * 关闭当前浮层。
     *
     * 直接派发一个 Escape keydown 到 document —— 这是 DSH 自己用的关闭手势。
     * 只在确实有浮层时才派发，避免在普通页面上凭空造出一个按键事件。
     */
    static final String DISMISS =
        "(function(){"
      + "  try{"
      + "    if(!window.__dshOverlayOpen) return 'none';"
      + "    var opts={key:'Escape', code:'Escape', keyCode:27, which:27,"
      + "              bubbles:true, cancelable:true};"
      + "    document.dispatchEvent(new KeyboardEvent('keydown', opts));"
      + "    window.dispatchEvent(new KeyboardEvent('keydown', opts));"
      + "    return 'dismissed';"
      + "  }catch(e){ return 'error'; }"
      + "})()";

    /**
     * 样式补丁。全部写在常驻 style 标签里，靠选择器命中（外层容器 + 直接子选择器），
     * 一次写入长期有效 —— 不跟 React 抢节点，也不怕它重渲染。
     *
     * 真机测量得到的病灶（见 PROBE 的 BOX/CSS 输出）：
     *
     *   1. mw0_9q_frame 是三列网格，实测 grid-template-columns: 56px 304px 0px。
     *      在 360px 视口里它按 720px 铺开，右栏算出来是 0 宽却仍占着位置。
     *      → 钉成「56px 导航 + 内容」两列，右栏移到第 2 行。
     *
     *   2. _tabLayout_ 是两列横向网格（空面板 + 真实面板各 360px），
     *      会话面板被顶到屏幕外。→ 钉成单列。
     *
     *   3. 设置抽屉 GWIfKa_panel 自带 max-width:312px，里面 nav 固定 188px，
     *      content 只剩 124px —— 中文被压成「一字一行」。
     *      → 全屏 + 纵向堆叠；导航列表横排成等宽几格；
     *        导航项内部改成「图标 + 文字」同行（靠 > 直接子选择器命中）。
     *
     *   4. 页头贴着状态栏，标题和时钟叠在一起。→ 给页头留出顶部内边距。
     *
     * 只改「容器几何」（宽度、栅格、方向、内边距），不动字号和配色：
     * 观感必须仍然是 DSH 自己那套，而不是被换皮。
     */
    static final String PATCH =
        "(function(){"
      + "  function log(m){ try{ console.log(m); }catch(e){} }"
      + "  try{"
      + "    var m=document.querySelector('meta[name=\"viewport\"]');"
      + "    if(!m){"
      + "      m=document.createElement('meta'); m.name='viewport';"
      + "      (document.head||document.documentElement).appendChild(m);"
      + "    }"
      + "    m.setAttribute('content',"
      + "      'width=device-width, initial-scale=1, maximum-scale=1, minimum-scale=1,"
      + " user-scalable=no, viewport-fit=cover');"
      + "    var head=document.head||document.documentElement;"
      + "    if(!document.getElementById('dsh-mobile-skin')){"
      + "      /*"
      + "       * 特异性提升。"
      + "       *"
      + "       * 站点自己那条主题/栅格规则的选择器比我们长，单写一个 !important 压不住"
      + "       * （实测 GWIfKa_panel 的 max-width:312px 就是这么赢的）。"
      + "       * 与其跟它比谁的选择器更花哨，不如把「一个 [class]」重复 20 次："
      + "       * 特异性直接拉到 (0,20,0) 这个量级，结构上没人能越过。"
      + "       * 这不是技巧，是这一层唯一稳的做法 —— 每次改样式都要带上它。"
      + "       */"
      + "      var P='';"
      + "      for(var pi=0;pi<20;pi++){ P+='[class]'; }"
      + "      var base=document.createElement('style');"
      + "      base.id='dsh-mobile-skin';"
      + "      base.textContent="
      + "          'html,body{width:100% !important;max-width:100% !important;"
      + "overflow-x:hidden !important;}'"
      + "        + 'body{overscroll-behavior-x:none;}'"
      + "        + '*,*::before,*::after{box-sizing:border-box;}'"
      + "        + 'img,video,canvas,svg,pre,table{max-width:100% !important;}'"
      + "        + 'pre{white-space:pre-wrap !important;word-break:break-word;}'"
      + "        /*"
      + "         * 手机端布局总纲，放在媒体查询里而不是运行时算死像素值 ——"
      + "         * 这样在任何窄屏设备上都成立，不依赖当前这台机器量到的 360px。"
      + "         */"
      + "        + '@media (max-width:600px){'"
      + "        /*"
      + "         * 侧栏宽度：**不要限制它**。"
      + "         *"
      + "         * 第一版在这里写了 --dsh-rail:46px 加 sidebarCol 的三条宽度限制，"
      + "         * 想把 56px 轨道省 10px 给正文。结果 DSH 展开侧栏时往 frame 写的行内样式"
      + "         * grid-template-columns:280px ... 被 max-width:46px 按死 ——「展开」"
      + "         * 在视觉上完全没反应，侧栏永远 46px、正文永远 314px，会话列表看不到。"
      + "         *"
      + "         * 现在轨道宽度与栅格完全交给 DSH 自己（56px ↔ 280px 本来就是对的），"
      + "         * 手机端只在它展开时把侧栏改成覆盖式抽屉 —— 见 MobileSkin.SIDEBAR。"
      + "         */"
      + "        + '[class*=sidebarCol] svg{transform:scale(.92);transform-origin:center;}'"
      + "        /*"
      + "         * 顶部让位给状态栏。"
      + "         *"
      + "         * 壳是全屏（FLAG_LAYOUT_NO_LIMITS），网页内容会画到状态栏底下，"
      + "         * 侧栏最上方的鲸鱼图标会和状态栏时钟叠在同一条线上。"
      + "         *"
      + "         * 高度取 MainActivity 推过来的 --dsh-safe-top（WindowInsets 的真实值），"
      + "         * 不再写死像素 —— 写死的 30px 是照着一台机器量的，换设备、换字号、"
      + "         * 横屏都不对，而状态栏多高本来就只有系统知道。"
      + "         * 兜底用 30px，保证变量还没送达时页面不会贴到状态栏上。"
      + "         */"
      + "        + '[class*=logoRow]{padding-top:var(--dsh-safe-top,30px) !important;}'"
      + "        /*"
      + "         * 会话页顶部那一行（标题 + 右上角图标）同样要让位。"
      + "         *"
      + "         * 踩过的坑：只给侧栏的 logoRow 加了让位，右上角那排图标所在的"
      + "         * titleRow 没加 —— 实测 titleRow 的 paddingTop 是 0px、t=10，"
      + "         * 也就是「打开右侧边栏」那个按钮**正压在状态栏里面**。"
      + "         *"
      + "         * 前缀不同（侧栏是 _6n5vyG_，这里是 vhIqGa_），一个规则管不到两处，"
      + "         * 所以按语义后缀各写一条。用 *= 匹配后缀而不是写死完整类名："
      + "         * CSS Module 的哈希前缀每个版本都会变，后缀是稳定的。"
      + "         */"
      + "        + '[class*=titleRow]{padding-top:var(--dsh-safe-top,30px) !important;}'"
      + "        + '[class*=headerCorner]{padding-top:0 !important;}'"
      + "        /*"
      + "         * 底部让位。"
      + "         *"
      + "         * 壳全屏 + 页面底部一直排到物理底边（792px 那一段含手势条区域），"
      + "         * 所以输入框和底部的用量条会被系统手势条压住一截。"
      + "         * --dsh-bottom 由 MobileSkin.KEYBOARD 运行时算好"
      + "         * （真实导航栏高度与 20px 取大者），这里只负责引用；"
      + "         * 变量还没送达时用 20px 兜底。"
      + "         *"
      + "         * 用 padding-bottom 而不是 height：它只影响内容区内边距，"
      + "         * 不会跟页面对 sticky 定位与内部滚动的计算打架。"
      + "         */"
      + "        + '[class*=composerSeat]{"
      + "padding-bottom:var(--dsh-bottom,20px) !important;}'"
      + "        + " + JS_COMPOSER_CSS
      + "        + '}'"
      + "        + '[class*=frame]{width:100% !important;max-width:100% !important;"
      + "min-width:0 !important;}'"
      + "        + '[class*=rightbarCol]{grid-column:2 !important;grid-row:2 !important;"
      + "width:auto !important;max-width:100% !important;min-width:0 !important;}'"
      + "        /*"
      + "         * centerCol 交给 DSH 自己算（它按栅格第一列宽度自动分配），"
      + "         * 这里只兜底防止它被撑出视口。侧栏的宽窄由 SIDEBAR 那段负责。"
      + "         */"
      + "        + '[class*=centerCol]{max-width:100% !important;min-width:0 !important;}'"
      + "        + '[class*=tabLayout]{grid-template-columns:minmax(0,1fr) !important;"
      + "width:100% !important;max-width:100% !important;overflow:hidden !important;}'"
      + "        + '[class*=emptyTabHost],[class*=pane_]{grid-column:1 !important;"
      + "width:100% !important;max-width:100% !important;min-width:0 !important;}'"
      + "        + 'div[class*=GWIfKa_overlay]{flex-direction:column !important;"
      + "align-items:stretch !important;}'"
      + "        + 'div[class*=GWIfKa_overlay] > div[class*=GWIfKa_panel]{"
      + "width:100% !important;max-width:100% !important;min-width:0 !important;"
      + "height:100% !important;max-height:100% !important;margin:0 !important;"
      + "border-radius:0 !important;flex-direction:column !important;}'"
      + "        + 'div[class*=GWIfKa_panel] > div[class*=GWIfKa_nav]{"
      + "width:100% !important;max-width:100% !important;min-width:0 !important;"
      + "height:auto !important;max-height:none !important;"
      + "flex:0 0 auto !important;flex-direction:column !important;}'"
      + "        + 'div[class*=GWIfKa_panel] > div[class*=GWIfKa_content]{"
      + "width:100% !important;max-width:100% !important;min-width:0 !important;"
      + "flex:1 1 auto !important;"
      + "/*"
      + " * min-height:0 是这条规则存在的**关键**，不是可有可无的收尾。"
      + " *"
      + " * flex 子项的 min-height 默认是 auto —— 意思是「不能比自己的内容更矮」。"
      + " * 于是 content 拒绝收缩，被内容撑到 958px，而 panel 只有 792px 且是"
      + " * overflow:hidden：多出来的 166px 被直接裁掉，用户既看不到也滚不到"
      + " * （真机现象：「Agent 预设页面超过屏幕长度且无法滑动」）。"
      + " *"
      + " * 改成 0 之后 content 才能收缩，其子项 options（本来就设了 min-height:0"
      + " * 与 overflow-y:auto）才真正成为滚动容器。"
      + " */"
      + "min-height:0 !important;"
      + "margin-left:0 !important;margin-right:0 !important;}'"
      + "        + 'div[class*=GWIfKa_content] > div[class*=GWIfKa_options]{"
      + "width:100% !important;max-width:100% !important;min-width:0 !important;"
      + "flex:1 1 auto !important;flex-direction:column !important;"
      + "overflow-y:auto !important;overflow-x:hidden !important;"
      + "min-height:0 !important;max-height:none !important;"
      + "margin-left:0 !important;margin-right:0 !important;}'"
      + "        + 'div[class*=GWIfKa_navList]{display:flex !important;"
      + "flex-direction:row !important;flex-wrap:wrap !important;"
      + "align-items:center !important;justify-content:flex-start !important;"
      + "gap:6px 10px !important;"
      + "width:100% !important;max-width:100% !important;"
      + "height:auto !important;max-height:none !important;margin:0 !important;"
      + "overflow:visible !important;}'"
      + "        + 'div[class*=GWIfKa_navList] > *{"
      + "display:inline-flex !important;flex-direction:row !important;"
      + "align-items:center !important;justify-content:center !important;"
      + "gap:5px !important;width:auto !important;min-width:0 !important;"
      + "height:auto !important;flex:0 0 auto !important;"
      + "min-height:0 !important;margin:0 !important;padding:4px 6px !important;"
      + "white-space:nowrap !important;"
      + "overflow:visible !important;}'"
      + "        + 'div[class*=GWIfKa_navList] > * *{"
      + "max-width:100% !important;overflow:visible !important;"
      + "white-space:nowrap !important;}'"
      + "        + 'div[class*=GWIfKa_header]{width:100% !important;"
      + "max-width:100% !important;min-width:0 !important;"
      + "padding-top:var(--dsh-safe-top,30px) !important;}'"
      + "        + 'div[class*=GWIfKa_options] > *{max-width:100% !important;}'"
      + "        + P+' div[class*=heroWorkspaceRow]{"
      + "display:flex !important;flex-direction:column !important;"
      + "flex-wrap:nowrap !important;"
      + "align-items:stretch !important;justify-content:flex-start !important;"
      + "gap:6px !important;"
      + "width:100% !important;max-width:100% !important;"
      + "height:auto !important;max-height:none !important;overflow:visible !important;}'"
      + "        + P+' div[class*=heroWorkspaceRow] > *{"
      + "flex:0 0 auto !important;width:100% !important;min-width:0 !important;"
      + "max-width:100% !important;overflow:visible !important;}'"
      + "        + P+' div[class*=heroWorkspaceRow] > * *{"
      + "min-width:0 !important;max-width:100% !important;}'"
      + "        + P+' div[class*=GWIfKa_nav]{height:auto !important;"
      + "max-height:none !important;flex:0 0 auto !important;}'"
      + "        + P+' div[class*=GWIfKa_navList]{width:100% !important;"
      + "max-width:100% !important;min-width:0 !important;"
      + "height:auto !important;max-height:none !important;"
      + "display:flex !important;flex-direction:row !important;"
      + "flex-wrap:wrap !important;overflow:visible !important;}'"
      + "        + P+' div[class*=GWIfKa_header]{"
      + "padding-top:var(--dsh-safe-top,30px) !important;}';"
      + "      head.appendChild(base);"
      + "    }"
      + "    console.log('PATCH-APPLIED');"
      + "  }catch(e){"
      + "    try{ log('PATCH-ERROR|'+(e&&e.message)); }catch(x){}"
      + "  }"
      + "})()";
}
