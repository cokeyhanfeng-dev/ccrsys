/** 标签栏吸顶时，其视口坐标不再代表内容起点；按内容坐标重置滚动。 */
export function resetDetailScroll(panel,toolbar){
  const scroll=panel?.closest('.scroll-body');if(!scroll)return;
  const height=toolbar?.offsetHeight||0;
  panel.style.minHeight=Math.max(0,scroll.clientHeight-height)+'px';
  scroll.scrollTo({top:Math.max(0,scroll.scrollTop+panel.getBoundingClientRect().top-scroll.getBoundingClientRect().top-height),behavior:'instant'});
}
