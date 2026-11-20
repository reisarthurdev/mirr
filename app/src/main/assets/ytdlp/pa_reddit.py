# Plugin do yt-dlp instalado pelo app "mirr": vídeos do Reddit.
# O extrator do yt-dlp usa como "id" o código do vídeo (v.redd.it/<código>), mas o app lista os posts
# pelo id do post (o "display_id"). Com ids diferentes o app achava que o vídeo baixado era outro post
# e baixava de novo a cada verificação. Aqui o id passa a ser o do post.
from yt_dlp.extractor.reddit import RedditIE


class ProfileArchiveRedditIE(RedditIE, plugin_name='profilearchive'):
    def _real_extract(self, url):
        info = super()._real_extract(url)
        if isinstance(info, dict) and info.get('_type') != 'playlist' and info.get('display_id'):
            info['id'] = info['display_id']
        return info
