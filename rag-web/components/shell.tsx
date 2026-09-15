"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  BookOpen,
  MessageSquareText,
  Search,
  FlaskConical,
  Menu,
  X,
} from "lucide-react";
import { cn } from "@/lib/utils";

const NAV_ITEMS = [
  { href: "/kb", label: "知识库", icon: BookOpen },
  { href: "/chat", label: "知识问答", icon: MessageSquareText },
  { href: "/debug", label: "检索调试", icon: Search },
  { href: "/eval", label: "效果评测", icon: FlaskConical },
] as const;

const THEME_KEY = "rag-theme";

export function applyTheme(pref: "light" | "dark" | "system") {
  const dark =
    pref === "dark" ||
    (pref === "system" &&
      window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", dark);
}

/** 主题分段控件（侧栏底部，Light/Dark/System，localStorage 持久化） */
export function ThemeToggle() {
  const [pref, setPref] = React.useState<"light" | "dark" | "system">("system");

  React.useEffect(() => {
    const saved = (localStorage.getItem(THEME_KEY) as typeof pref) || "system";
    setPref(saved);
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => applyTheme((localStorage.getItem(THEME_KEY) as typeof pref) || "system");
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  const choose = (p: typeof pref) => {
    localStorage.setItem(THEME_KEY, p);
    setPref(p);
    applyTheme(p);
  };

  const options: { value: typeof pref; label: string }[] = [
    { value: "light", label: "浅色" },
    { value: "dark", label: "深色" },
    { value: "system", label: "系统" },
  ];

  return (
    <div
      role="group"
      aria-label="主题"
      className="flex w-full overflow-hidden rounded-md border border-border"
    >
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => choose(o.value)}
          aria-pressed={pref === o.value}
          className={cn(
            "flex-1 px-2 py-1.5 text-[13px] transition-colors",
            pref === o.value
              ? "bg-primary-subtle font-medium text-primary"
              : "text-muted-foreground hover:bg-muted hover:text-foreground"
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

function SidebarNav({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();
  return (
    <nav className="flex flex-1 flex-col gap-1 px-3">
      {NAV_ITEMS.map((item) => {
        // 文档管理/文档详情属于知识库域
        const active =
          item.href === "/kb"
            ? pathname.startsWith("/kb")
            : pathname.startsWith(item.href);
        const Icon = item.icon;
        return (
          <Link
            key={item.href}
            href={item.href}
            onClick={onNavigate}
            aria-current={active ? "page" : undefined}
            className={cn(
              "flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors",
              active
                ? "bg-primary-subtle font-medium text-primary"
                : "text-foreground hover:bg-muted"
            )}
          >
            <Icon className="size-4 shrink-0" aria-hidden />
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}

function SidebarBody({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2 px-4 pb-4 pt-4">
        <span
          aria-hidden
          className="flex size-7 items-center justify-center rounded-md bg-primary text-[13px] font-semibold text-primary-foreground"
        >
          知
        </span>
        <span className="text-sm font-semibold">通用知识问答 RAG</span>
      </div>
      <SidebarNav onNavigate={onNavigate} />
      <div className="border-t border-border p-3">
        <ThemeToggle />
      </div>
    </div>
  );
}

/**
 * 应用外壳：232px 浅冷灰侧栏（桌面常驻 / 窄屏抽屉）。
 * 页面通过 <Topbar> 提供汉堡按钮与面包屑。
 */
export function Shell({ children }: { children: React.ReactNode }) {
  const [drawerOpen, setDrawerOpen] = React.useState(false);

  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setDrawerOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  // 路由变化时收起抽屉
  const pathname = usePathname();
  React.useEffect(() => setDrawerOpen(false), [pathname]);

  return (
    <ShellContext.Provider value={{ openSidebar: () => setDrawerOpen(true) }}>
      <div className="flex h-dvh overflow-hidden">
        {/* 桌面侧栏 */}
        <aside className="hidden w-[232px] flex-none border-r border-border bg-surface-subtle md:block">
          <SidebarBody />
        </aside>

        {/* 窄屏抽屉 */}
        {drawerOpen && (
          <div className="fixed inset-0 z-50 md:hidden">
            <div
              className="absolute inset-0 bg-slate-900/40"
              onClick={() => setDrawerOpen(false)}
              aria-hidden
            />
            <aside className="absolute inset-y-0 left-0 w-[232px] border-r border-border bg-surface-subtle shadow-lg">
              <button
                type="button"
                className="absolute right-2 top-2 rounded-sm p-1.5 text-muted-foreground hover:bg-muted"
                onClick={() => setDrawerOpen(false)}
                aria-label="关闭导航"
              >
                <X className="size-4" />
              </button>
              <SidebarBody onNavigate={() => setDrawerOpen(false)} />
            </aside>
          </div>
        )}

        <div className="flex min-w-0 flex-1 flex-col">{children}</div>
      </div>
    </ShellContext.Provider>
  );
}

const ShellContext = React.createContext<{ openSidebar: () => void }>({
  openSidebar: () => {},
});

export function useShell() {
  return React.useContext(ShellContext);
}

/** 页面顶栏：汉堡（窄屏）+ 面包屑 + 右侧附加区（如知识库选择器） */
export function Topbar({
  crumbs,
  extra,
}: {
  crumbs: { label: string; href?: string }[];
  extra?: React.ReactNode;
}) {
  const { openSidebar } = useShell();
  return (
    <header className="flex h-14 flex-none items-center gap-3 border-b border-border bg-surface px-4">
      <button
        type="button"
        className="rounded-sm p-1.5 text-muted-foreground hover:bg-muted md:hidden"
        onClick={openSidebar}
        aria-label="打开导航"
      >
        <Menu className="size-5" />
      </button>
      <div className="flex min-w-0 items-center gap-2 text-sm">
        {crumbs.map((c, i) => (
          <React.Fragment key={i}>
            {i > 0 && <span className="text-muted-foreground">/</span>}
            {c.href ? (
              <Link
                href={c.href}
                className="truncate text-muted-foreground hover:text-primary"
              >
                {c.label}
              </Link>
            ) : (
              <span className="truncate font-semibold">{c.label}</span>
            )}
          </React.Fragment>
        ))}
      </div>
      {extra && <div className="ml-auto flex items-center gap-2">{extra}</div>}
    </header>
  );
}
