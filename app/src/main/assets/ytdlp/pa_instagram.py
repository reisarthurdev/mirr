# Plugin do yt-dlp instalado pelo app "mirr": listagem de perfis do Instagram.
# O extrator de perfil do próprio yt-dlp (instagram:user) está quebrado (_WORKING = False).
# Aqui a listagem usa a API web do Instagram:
#   1. users/web_profile_info: dados do perfil + os posts mais recentes (funciona sem login em perfil público)
#   2. feed/user/<id>: todos os posts, paginado (normalmente exige login/cookies)
#   3. sem login, tenta a paginação antiga por GraphQL; se o Instagram recusar, fica com o que veio no passo 1
# Cada post sai com:
#   slides      fotos (e vídeos de carrossel) em resolução máxima: o app baixa direto (o yt-dlp não baixa fotos)
#   pa_formats  links de vídeo do post (o app usa se o caminho normal do yt-dlp falhar)
#   thumbnail   capa do post
# E o perfil sai com: description (bio), channel_follower_count, thumbnails[avatar], pa_total (posts no perfil).
import itertools
import json

from yt_dlp.extractor.instagram import InstagramIE, InstagramUserIE
from yt_dlp.networking.exceptions import HTTPError
from yt_dlp.utils import ExtractorError, int_or_none, traverse_obj

_LEGACY_TIMELINE_HASH = '69cba40317214236af40e7efa697781d'

# Com login, a API "do app de iPhone" (i.instagram.com) é a que o Instagram menos limita;
# é o mesmo caminho que o yt-dlp (app_id=ios) e o instaloader usam.
_IOS_APP_ID = '124024574287414'
_IOS_UA = ('Instagram 361.0.0.35.82 (iPhone15,3; iOS 18_1; pt_BR; pt-BR; scale=3.00; 1290x2796; 674117118) '
           'AppleWebKit/420+')


def _by_size(cands):
    """Candidatos {url, width, height} do maior para o menor."""
    good = [c for c in (cands or []) if isinstance(c, dict) and isinstance(c.get('url'), str)]
    return sorted(good, key=lambda c: (c.get('width') or 0) * (c.get('height') or 0), reverse=True)


def _slide_from_feed(m):
    images = _by_size(traverse_obj(m, ('image_versions2', 'candidates')))
    if m.get('media_type') == 2:
        videos = _by_size(m.get('video_versions'))
        if videos:
            return {'urls': [v['url'] for v in videos], 'video': True,
                    'width': videos[0].get('width'), 'height': videos[0].get('height'),
                    'cover': images[0]['url'] if images else None}
    if images:
        return {'urls': [i['url'] for i in images], 'width': images[0].get('width'), 'height': images[0].get('height')}
    return None


def _slide_from_node(n):
    dims = n.get('dimensions') or {}
    if n.get('is_video') and n.get('video_url'):
        return {'urls': [n['video_url']], 'video': True, 'width': dims.get('width'), 'height': dims.get('height'),
                'cover': n.get('display_url')}
    resources = _by_size([{'url': r.get('src'), 'width': r.get('config_width'), 'height': r.get('config_height')}
                          for r in (n.get('display_resources') or [])])
    urls = [r['url'] for r in resources] or ([n['display_url']] if n.get('display_url') else [])
    return {'urls': urls, 'width': dims.get('width'), 'height': dims.get('height')} if urls else None


class ProfileArchiveInstagramUserIE(InstagramUserIE, plugin_name='profilearchive'):
    _WORKING = True

    def _headers(self, username):
        h = {**self._api_headers, 'X-Requested-With': 'XMLHttpRequest', 'Referer': f'https://www.instagram.com/{username}/'}
        csrf = self._get_cookies('https://www.instagram.com/').get('csrftoken')
        if csrf:
            h['X-CSRFToken'] = csrf.value
        return h

    def _routes(self, username):
        """(base da API, cabeçalhos) em ordem de preferência: app de iPhone (com login) e depois web."""
        web = ('https://www.instagram.com/api/v1', self._headers(username))
        if not self._is_logged_in:
            return [web]
        ios = ('https://i.instagram.com/api/v1', {
            'User-Agent': _IOS_UA, 'X-IG-App-ID': _IOS_APP_ID, 'X-IG-Capabilities': '3brTvx0=',
            'X-IG-Connection-Type': 'WIFI', 'Accept-Language': 'pt-BR', 'Accept': '*/*'})
        return [ios, web]

    def _api(self, username, path, note, query=None, fatal=True):
        """Chama a API pela 1ª rota que responder; 429 (limite) numa rota tenta a próxima."""
        last = None
        for base, headers in self._routes(username):
            try:
                return self._download_json(f'{base}/{path}', username, note, query=query, headers=headers)
            except ExtractorError as e:
                last = e
                if not (isinstance(e.cause, HTTPError) and e.cause.status in (401, 403, 429)):
                    break
        if not fatal:
            return None
        if last and isinstance(last.cause, HTTPError) and last.cause.status == 429:
            raise ExtractorError('O Instagram limitou as consultas (erro 429). Espere alguns minutos e tente de novo.',
                                 expected=True)
        raise last

    def _real_extract(self, url):
        username = self._match_id(url)
        headers = self._headers(username)
        data = self._api(username, 'users/web_profile_info/', 'Downloading profile info', query={'username': username})
        user = traverse_obj(data, ('data', 'user', {dict})) or {}
        if not user.get('id'):
            raise ExtractorError('Perfil não encontrado ou o Instagram bloqueou a consulta', expected=True)
        if user.get('is_private') and not self._is_logged_in:
            self.raise_login_required('Perfil privado')
        timeline = user.get('edge_owner_to_timeline_media') or {}
        avatar = user.get('profile_pic_url_hd') or user.get('profile_pic_url')
        # dados do perfil também em cada post: o app lê do 1º e não precisa de uma consulta só para o perfil
        self._profile = {
            'pa_avatar': avatar, 'pa_bio': user.get('biography') or None, 'pa_name': user.get('full_name') or None,
            'pa_followers': traverse_obj(user, ('edge_followed_by', 'count', {int_or_none})),
            'pa_total': int_or_none(timeline.get('count')),
        }
        return self.playlist_result(
            self._entries(username, user['id'], headers, timeline, avatar), username, username,
            description=user.get('biography'),
            uploader=user.get('full_name') or username, channel=user.get('full_name') or username,
            channel_follower_count=traverse_obj(user, ('edge_followed_by', 'count', {int_or_none})),
            thumbnails=[{'id': 'avatar', 'url': avatar}] if avatar else None,
            pa_total=int_or_none(timeline.get('count')))

    # ───────── posts ─────────

    def _entry(self, code, extra):
        meta = {k: v for k, v in (getattr(self, '_profile', None) or {}).items() if v is not None}
        return self.url_result(f'https://www.instagram.com/p/{code}/', InstagramIE, code, **{**meta, **extra})

    def _from_feed(self, it, avatar):
        code = it.get('code')
        if not code:
            return None
        caption = traverse_obj(it, ('caption', 'text', {str})) or ''
        cover = _by_size(traverse_obj(it, ('image_versions2', 'candidates')))
        extra = {
            'title': caption[:72] or code, 'description': caption,
            'timestamp': int_or_none(it.get('taken_at')),
            'like_count': int_or_none(it.get('like_count')), 'comment_count': int_or_none(it.get('comment_count')),
            'view_count': int_or_none(it.get('play_count') or it.get('view_count')),
            'width': int_or_none(it.get('original_width')), 'height': int_or_none(it.get('original_height')),
            'thumbnail': cover[0]['url'] if cover else None, 'pa_avatar': avatar,
        }
        mt = it.get('media_type')
        if mt == 2:
            videos = _by_size(it.get('video_versions'))
            extra['pa_formats'] = [{'url': v['url'], 'width': v.get('width'), 'height': v.get('height'),
                                    'ext': 'mp4', 'format_id': f'feed-{i}'} for i, v in enumerate(videos)]
        elif mt == 8:
            extra['slides'] = [s for s in map(_slide_from_feed, it.get('carousel_media') or []) if s]
        else:
            s = _slide_from_feed(it)
            extra['slides'] = [s] if s else []
        return self._entry(code, extra)

    def _from_node(self, n, avatar):
        code = n.get('shortcode')
        if not code:
            return None
        caption = traverse_obj(n, ('edge_media_to_caption', 'edges', 0, 'node', 'text', {str})) or ''
        dims = n.get('dimensions') or {}
        extra = {
            'title': caption[:72] or code, 'description': caption,
            'timestamp': int_or_none(n.get('taken_at_timestamp')),
            'like_count': traverse_obj(n, (('edge_liked_by', 'edge_media_preview_like'), 'count', {int_or_none}), get_all=False),
            'comment_count': traverse_obj(n, ('edge_media_to_comment', 'count', {int_or_none})),
            'view_count': int_or_none(n.get('video_view_count')),
            'width': dims.get('width'), 'height': dims.get('height'),
            'thumbnail': n.get('display_url'), 'pa_avatar': avatar,
        }
        children = traverse_obj(n, ('edge_sidecar_to_children', 'edges', ..., 'node', {dict}))
        if children:
            extra['slides'] = [s for s in map(_slide_from_node, children) if s]
        elif n.get('is_video'):
            if n.get('video_url'):
                extra['pa_formats'] = [{'url': n['video_url'], 'width': dims.get('width'), 'height': dims.get('height'),
                                        'ext': 'mp4', 'format_id': 'graphql'}]
        else:
            s = _slide_from_node(n)
            extra['slides'] = [s] if s else []
        return self._entry(code, extra)

    def _entries(self, username, uid, headers, timeline, avatar):
        seen = set()

        def fresh(e):
            if e and e['id'] not in seen:
                seen.add(e['id'])
                return True
            return False

        # 1) feed paginado (com login traz o perfil inteiro)
        max_id = None
        for page in itertools.count(1):
            query = {'count': 33, **({'max_id': max_id} if max_id else {})}
            data = self._api(username, f'feed/user/{uid}/', f'Downloading feed page {page}', query=query, fatal=False) or {}
            items = traverse_obj(data, ('items', ..., {dict})) or []
            for it in items:
                e = self._from_feed(it, avatar)
                if fresh(e):
                    yield e
            max_id = data.get('next_max_id')
            if not items or not data.get('more_available') or not max_id:
                break
        if seen:
            return

        # 2) sem login: posts que vieram junto com o perfil
        for n in traverse_obj(timeline, ('edges', ..., 'node', {dict})):
            e = self._from_node(n, avatar)
            if fresh(e):
                yield e

        # 3) paginação antiga por GraphQL (o Instagram costuma recusar sem login; aí paramos)
        cursor = traverse_obj(timeline, ('page_info', 'end_cursor'))
        has_next = traverse_obj(timeline, ('page_info', 'has_next_page'))
        while has_next and cursor:
            data = self._download_json(
                'https://www.instagram.com/graphql/query/', username, 'Downloading more posts',
                query={'query_hash': _LEGACY_TIMELINE_HASH,
                       'variables': json.dumps({'id': uid, 'first': 50, 'after': cursor})},
                headers=headers, fatal=False, errnote=False) or {}
            media = traverse_obj(data, ('data', 'user', 'edge_owner_to_timeline_media', {dict})) or {}
            nodes = traverse_obj(media, ('edges', ..., 'node', {dict})) or []
            if not nodes:
                break
            for n in nodes:
                e = self._from_node(n, avatar)
                if fresh(e):
                    yield e
            cursor = traverse_obj(media, ('page_info', 'end_cursor'))
            has_next = traverse_obj(media, ('page_info', 'has_next_page'))
