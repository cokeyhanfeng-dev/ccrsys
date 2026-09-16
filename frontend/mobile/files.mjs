/** 只预览确认的 PDF/光栅图片；不把上传 MIME 或文件扩展名作为可信内容类型。 */
export function previewType(bytes){
  const b=Array.from(bytes),starts=arr=>arr.every((v,i)=>b[i]===v);
  if(starts([37,80,68,70,45]))return 'application/pdf';
  if(starts([137,80,78,71,13,10,26,10]))return 'image/png';
  if(starts([255,216,255]))return 'image/jpeg';
  if(starts([71,73,70,56])&&(b[4]===55||b[4]===57)&&b[5]===97)return 'image/gif';
  return null;
}

/** 扩展名仅用于列表图标；实际预览仍校验文件签名。 */
export function attachmentKind(name=''){
  const extension=String(name).trim().split('.').pop().toLowerCase();
  if(extension==='pdf')return 'pdf';
  return ['jpg','jpeg','png','gif','webp','bmp','heic','heif','tif','tiff'].includes(extension)?'image':'file';
}
