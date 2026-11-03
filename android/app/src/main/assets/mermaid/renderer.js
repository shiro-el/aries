/* Aries adapter for the pinned offline Pele bundle. Source is data, never JavaScript. */
function ariesMermaidSvg(source, width) {
    try {
        return pele.render(source, {
            maxWidth: width, responsive: true, autoDirection: true,
            limit: 65536, maxEdges: 500, outputLimit: 2097152,
            images: false, linkSchemes: ['https', 'http', 'mailto'],
            idPrefix: 'aries-mermaid-'
        });
    } catch (_) {
        return null;
    }
}
function ariesMermaidShow(source, width, zoom) {
    const result = ariesMermaidSvg(source, width);
    if (!result) return null;
    const container = document.getElementById('diagram');
    container.innerHTML = result.svg;
    if (zoom) pele.enableZoom(container, {always: true, controls: false, maxScale: 8});
    return {width: result.width, height: result.height};
}
