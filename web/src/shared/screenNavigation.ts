import { useEffect, useState } from 'react';
export type Section = 'accounts' | 'operations' | 'more';
const screen = (): Section => location.hash === '#operations' ? 'operations' : location.hash === '#more' ? 'more' : 'accounts';
export function useScreenNavigation() {
  const [section, setSection] = useState<Section>(screen);
  useEffect(() => {
    const requested = screen();
    if (!history.state?.capitalScreen) {
      history.replaceState({ capitalScreen: true, depth: 0 }, '', '#accounts');
      if (requested !== 'accounts') history.pushState({ capitalScreen: true, depth: 1 }, '', `#${requested}`);
    }
    const update = () => { setSection(screen()); window.scrollTo(0, 0); };
    window.addEventListener('popstate', update);
    window.addEventListener('hashchange', update);
    return () => { window.removeEventListener('popstate', update); window.removeEventListener('hashchange', update); };
  }, []);
  function navigate(next: Section) {
    if (next === section) return;
    const depth = history.state?.capitalScreen ? Number(history.state.depth) || 0 : 0;
    if (next === 'accounts' && depth > 0) { history.go(-depth); return; }
    history.pushState({ capitalScreen: true, depth: next === 'accounts' ? 0 : depth + 1 }, '', `#${next}`);
    setSection(next); window.scrollTo(0, 0);
  }
  function back() {
    if (history.state?.capitalScreen && history.state.depth > 0) history.back();
    else navigate('accounts');
  }
  return { section, navigate, back };
}
