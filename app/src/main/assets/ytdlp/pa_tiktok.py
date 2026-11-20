# Plugin do yt-dlp instalado pelo app "mirr".
# Acrescenta ao resultado do TikTok (inclusive na listagem rápida do perfil, --flat-playlist):
#   slides        URLs das fotos em resolução cheia dos posts de fotos (o yt-dlp só baixaria a música)
#   slides_audio  URL da música desses posts
#   pa_formats    links de vídeo que já vêm na listagem do perfil (o app baixa por eles quando a
#                 página do vídeo vem sem links, ex.: posts marcados como sensíveis)
#   pa_avatar     foto de perfil do autor
#   pa_bio        bio do autor; pa_followers: seguidores
#   pa_music      música do post: title, author, original, cover
# E tenta mais uma forma de achar o secUid quando a página do perfil vem bloqueada.
# Tudo protegido por try/except: se o TikTok mudar algo, o yt-dlp segue funcionando normalmente.
import re

from yt_dlp.extractor.tiktok import TikTokIE, TikTokUserIE

_SEC_UID_RE = r'"secUid"\s*:\s*"(MS4wLjABAAAA[\w-]{20,})"'


def _first_url(*candidates):
    for c in candidates:
        if isinstance(c, str) and c.startswith('http'):
            return c
        if isinstance(c, list):
            for u in c:
                if isinstance(u, str) and u.startswith('http'):
                    return u
    return None


def _slides(detail):
    out = []
    for img in ((detail.get('imagePost') or {}).get('images') or []):             # API web
        urls = (img.get('imageURL') or {}).get('urlList') or []
        if urls:
            out.append({'urls': urls, 'width': img.get('imageWidth'), 'height': img.get('imageHeight')})
    if not out:
        for img in ((detail.get('image_post_info') or {}).get('images') or []):      # API do app
            disp = img.get('display_image') or {}
            urls = disp.get('url_list') or []
            if urls:
                out.append({'urls': urls, 'width': disp.get('width'), 'height': disp.get('height')})
    return out


def _cover(m):
    """Capa (maior primeiro) nos dois formatos: web (coverLarge) e app (cover_large.url_list)."""
    return _first_url(m.get('coverLarge'), m.get('coverMedium'), m.get('coverThumb'),
                      *[(m.get(k) or {}).get('url_list') for k in ('cover_hd', 'cover_large', 'cover_medium', 'cover_thumb')])


def _matched(music):
    """Música reconhecida dentro de um som original (o "Contém:" do app do TikTok)."""
    for k in ('matched_song', 'matchedSong', 'matched_pgc_sound', 'matchedPgcSound'):
        m = music.get(k)
        if isinstance(m, dict) and (m.get('title') or m.get('mixed_title') or m.get('mixedTitle')):
            return m
    return None


def _matched_author(m):
    author = m.get('author') or m.get('mixed_author') or m.get('mixedAuthor')
    if isinstance(author, str) and author:
        return author
    names = [p.get('name') for p in (m.get('performers') or []) if isinstance(p, dict) and p.get('name')]
    return ', '.join(names) or None


def _extras(ie, info, detail, flat):
    if not isinstance(info, dict) or not isinstance(detail, dict):
        return info
    try:
        music = detail.get('music') or {}
        author = detail.get('author') if isinstance(detail.get('author'), dict) else {}
        slides = _slides(detail)
        if slides:
            info['slides'] = slides
            audio = _first_url(music.get('playUrl'), (music.get('play_url') or {}).get('url_list'))
            if audio:
                info['slides_audio'] = audio
        elif flat and isinstance(detail.get('video'), dict):
            formats = [f for f in (ie._extract_web_formats(detail) or []) if f.get('vcodec') != 'none']
            if formats:
                info['pa_formats'] = formats
        # música do post para o disco do player
        cover = _cover(music)
        song = {
            'title': music.get('title') or None,
            'author': music.get('authorName') or (music.get('author') if isinstance(music.get('author'), str) else None),
            'original': bool(music.get('original') or music.get('is_original_sound') or music.get('is_original')),
            'cover': cover,
        }
        # "som original" que o TikTok reconheceu como uma música conhecida: o app mostra
        # "Contém: <música>" com a capa do álbum. Esses dados vêm em matched_song (API do app) ou
        # matched_pgc_sound; os nomes em camelCase cobrem a API web.
        matched = _matched(music)
        if matched:
            song = {
                'title': matched.get('title') or matched.get('mixed_title') or matched.get('mixedTitle'),
                'author': _matched_author(matched),
                'original': False,
                'contains': bool(song['original']),
                'cover': _cover(matched) or cover,
            }
        if song['title'] or song['author'] or song['cover']:
            info['pa_music'] = song
        avatar = _first_url(author.get('avatarLarger'), author.get('avatarMedium'),
                            (author.get('avatar_larger') or {}).get('url_list'))
        if avatar:
            info['pa_avatar'] = avatar
        # bio e seguidores do autor (a listagem do perfil no yt-dlp não traz esses dados)
        bio = author.get('signature')
        if isinstance(bio, str) and bio.strip():
            info['pa_bio'] = bio.strip()
        stats = detail.get('authorStatsV2') or detail.get('authorStats') or {}
        try:
            followers = int(stats.get('followerCount'))
            info['pa_followers'] = followers
        except (TypeError, ValueError):
            pass
    except Exception:
        pass
    return info


class ProfileArchiveTikTokIE(TikTokIE, plugin_name='profilearchive'):
    def _parse_aweme_video_web(self, aweme_detail, webpage_url, video_id, extract_flat=False, *args, **kwargs):
        info = super()._parse_aweme_video_web(aweme_detail, webpage_url, video_id, extract_flat, *args, **kwargs)
        return _extras(self, info, aweme_detail, extract_flat)

    def _parse_aweme_video_app(self, aweme_detail, *args, **kwargs):
        return _extras(self, super()._parse_aweme_video_app(aweme_detail, *args, **kwargs), aweme_detail, False)


class ProfileArchiveTikTokUserIE(TikTokUserIE, plugin_name='profilearchive'):
    # a listagem do perfil chama _parse_aweme_video_web(..., extract_flat=True) para cada post
    def _parse_aweme_video_web(self, aweme_detail, webpage_url, video_id, extract_flat=False, *args, **kwargs):
        info = super()._parse_aweme_video_web(aweme_detail, webpage_url, video_id, extract_flat, *args, **kwargs)
        return _extras(self, info, aweme_detail, extract_flat)

    def _extract_sec_uid_from_embed(self, user_name):
        try:
            sec_uid = super()._extract_sec_uid_from_embed(user_name)
        except Exception:
            sec_uid = None
        return sec_uid or self._pa_sec_uid_from_profile(user_name)

    def _pa_sec_uid_from_profile(self, user_name):
        url = self._UPLOADER_URL_FORMAT % user_name
        try:
            headers = self._generate_blockbuster_headers()
        except Exception:
            headers = {}

        def fetch(note):
            try:
                return self._download_webpage(url, user_name, note, fatal=False, impersonate=True, headers=headers) or ''
            except Exception:
                return ''

        page = fetch('Downloading user webpage (retry)')
        found = re.search(_SEC_UID_RE, page)
        if not found and page and hasattr(self, '_solve_challenge_and_set_cookies'):
            try:
                self._solve_challenge_and_set_cookies(page)
                page = fetch('Downloading user webpage with challenge cookie')
                found = re.search(_SEC_UID_RE, page)
            except Exception:
                pass
        return found.group(1) if found else None
