/**
 * 업로드 크기 제한 대응 (운영 게이트웨이 요청 본문 한도 1MB).
 * - 사진: 브라우저에서 긴 변 1600px·JPEG 로 줄여 한도 아래로 맞춤
 * - 그 밖의 파일(PDF·엑셀): 한도를 넘으면 나눠 올리도록 안내
 */
export const UPLOAD_LIMIT = 900 * 1024   // multipart 머리글 여유를 둔 실제 파일 한도

export const fmtSize = (n: number) => n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.ceil(n / 1024)}KB`

async function loadImage(file: File): Promise<HTMLImageElement> {
  const url = URL.createObjectURL(file)
  try {
    const img = new Image()
    img.src = url
    await img.decode()
    return img
  } finally { setTimeout(() => URL.revokeObjectURL(url), 1000) }
}

async function shrinkImage(file: File): Promise<File> {
  const img = await loadImage(file)
  for (const maxSide of [1600, 1280, 1024, 800]) {
    const scale = Math.min(1, maxSide / Math.max(img.naturalWidth, img.naturalHeight))
    const c = document.createElement('canvas')
    c.width = Math.round(img.naturalWidth * scale); c.height = Math.round(img.naturalHeight * scale)
    const ctx = c.getContext('2d')!
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height)   // 투명 PNG → 흰 배경
    ctx.drawImage(img, 0, 0, c.width, c.height)
    for (const q of [0.85, 0.75, 0.6, 0.45]) {
      const blob: Blob | null = await new Promise(r => c.toBlob(r, 'image/jpeg', q))
      if (blob && blob.size <= UPLOAD_LIMIT)
        return new File([blob], file.name.replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg' })
    }
  }
  throw new Error('사진을 충분히 줄이지 못했습니다. 다른 사진을 골라 주세요.')
}

/** 올리기 전에 호출: 한도 이하면 그대로, 큰 사진은 줄이고, 큰 문서는 안내 오류 */
export async function prepareUpload(file: File): Promise<File> {
  if (file.size <= UPLOAD_LIMIT) return file
  if (file.type.startsWith('image/')) return shrinkImage(file)
  throw new Error(`파일이 너무 큽니다 (${fmtSize(file.size)}). ${fmtSize(UPLOAD_LIMIT)} 이하로 나누거나 줄여서 올려 주세요.`)
}
