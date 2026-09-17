export function imageScale(value){return Math.max(1,Math.min(5,Number.isFinite(value)?value:1));}
/** 屏幕中心为原点，缩放时保持手指下的图像位置。 */
export function imageZoomAt(pan,point,before,after){return {x:point.x-(point.x-pan.x)*after/before,y:point.y-(point.y-pan.y)*after/before};}
