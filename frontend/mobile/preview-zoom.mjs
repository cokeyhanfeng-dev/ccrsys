export const clampZoom = value => Math.min(3, Math.max(1, Number.isFinite(value) ? value : 1));
export const touchDistance = touches => Math.hypot(touches[0].clientX-touches[1].clientX,touches[0].clientY-touches[1].clientY);
export function zoomAnchor(scroll, point, before, after) {return Math.max(0, (scroll+point)*after/before-point);}
