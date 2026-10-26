const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { createRequire } = require('node:module');
const bundledRequire = process.env.FLOWCHART_NODE_MODULES
  ? createRequire(path.join(process.env.FLOWCHART_NODE_MODULES, '__verify__.cjs'))
  : require;
const { chromium } = bundledRequire('playwright');
async function main() {
  const browser = await chromium.launch({channel:'msedge',headless:true});
  const context = await browser.newContext({viewport:{width:1440,height:1000},deviceScaleFactor:1});
  const page = await context.newPage();
  const errors = []; const externalRequests = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (/^https?:/.test(request.url())) externalRequests.push(request.url()); });
  await page.goto(pathToFileURL(path.join(__dirname,'project-flowcharts.html')).href);
  await page.screenshot({path:path.join(__dirname,'preview-overview.png')});
  const diagrams = await page.locator('.diagram').count();
  const headings = await page.locator('h2[id]').count();
  const navLinks = await page.locator('nav a').count();
  const brokenLinks = await page.evaluate(() => [...document.querySelectorAll('a[href]')].filter(a => a.hash && !document.getElementById(a.hash.slice(1)) && a.getAttribute('href').startsWith('#')).map(a=>a.href));
  const textOverflows = await page.evaluate(() => {
    const result=[];
    for (const diagram of document.querySelectorAll('.diagram')) {
      for(const node of diagram.querySelectorAll('.node')) {
        const shape=node.querySelector('polygon,ellipse,path'); if(!shape) continue;
        const box=shape.getBBox();
        for(const text of node.querySelectorAll('text')) {
          const b=text.getBBox();
          if(b.x < box.x-3 || b.x+b.width > box.x+box.width+3) result.push({diagram:diagram.dataset.diagram,text:text.textContent});
        }
      }
    }
    return result;
  });
  const overlappingLabels = await page.evaluate(() => {
    const issues=[];
    for(const diagram of document.querySelectorAll('.diagram')) {
      const labels=[...diagram.querySelectorAll('svg text')];
      for(let i=0;i<labels.length;i++) for(let j=i+1;j<labels.length;j++) {
        const a=labels[i].getBBox(), b=labels[j].getBBox();
        if(Math.min(a.x+a.width,b.x+b.width)-Math.max(a.x,b.x)>2 && Math.min(a.y+a.height,b.y+b.height)-Math.max(a.y,b.y)>2) issues.push({diagram:diagram.dataset.diagram,a:labels[i].textContent,b:labels[j].textContent});
      }
    }
    return issues;
  });
  const interactions=[];
  for (const button of await page.locator('.expand-diagram').all()) {
    const id=await button.getAttribute('data-id');
    await button.click();
    await page.locator('#diagram-dialog').waitFor({state:'visible'});
    const oldWidth=await page.locator('#dialog-content svg').evaluate(el=>el.getBoundingClientRect().width);
    await page.locator('#zoom-in').click();
    const enlarged=await page.locator('#dialog-content svg').evaluate(el=>el.getBoundingClientRect().width);
    await page.locator('#zoom-out').click();
    if(id==='08-moodle-assignments') await page.screenshot({path:path.join(__dirname,'preview-diagram.png')});
    await page.locator('#dialog-close').click();
    interactions.push({id,zoomWorks:enlarged>oldWidth,closed:!(await page.locator('#diagram-dialog').isVisible())});
  }
  const desktopOverflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
  await page.setViewportSize({width:390,height:844});
  await page.evaluate(()=>scrollTo(0,0));
  await page.screenshot({path:path.join(__dirname,'preview-mobile.png')});
  const mobileOverflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
  const overflowElements=await page.evaluate(()=>[...document.querySelectorAll('article *')].filter(el=>el.getBoundingClientRect().right>innerWidth && !el.closest('.diagram-scroll') && !el.closest('table') && !el.closest('pre')).slice(0,12).map(el=>({tag:el.tagName,cls:el.className,right:el.getBoundingClientRect().right,text:el.textContent.slice(0,100)})));
  const unresolved=await page.evaluate(()=>document.body.innerText.includes('<!-- diagram:') || document.body.innerText.includes('undefined'));
  const report={checkedAt:new Date().toISOString(),browser:'Installed Microsoft Edge, headless via Playwright',diagrams,headings,navLinks,brokenLinks,textOverflows,overlappingLabels,externalRequests,errors,desktopOverflow,mobileOverflow,overflowElements,unresolved,interactions};
  fs.writeFileSync(path.join(__dirname,'verification.json'),JSON.stringify(report,null,2)+'\n');
  await browser.close();
  console.log(JSON.stringify(report));
  if(diagrams!==16 || headings!==21 || navLinks!==21 || errors.length || externalRequests.length || brokenLinks.length || textOverflows.length || overlappingLabels.length || desktopOverflow || mobileOverflow || unresolved || interactions.some(x=>!x.zoomWorks||!x.closed)) process.exitCode=1;
}
main().catch(e=>{console.error(e);process.exitCode=1;});
