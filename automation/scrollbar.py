"""Quiet, rounded vertical scrollbar for native Tk surfaces."""
import tkinter as tk


class SlimScrollbar(tk.Canvas):
    def __init__(self, parent, command):
        try:
            background = parent.cget('bg')
        except tk.TclError:
            background = '#19211e'
        super().__init__(parent, width=14, bg=background, highlightthickness=0,
                         bd=0, takefocus=1)
        self.command = command
        self.first, self.last = 0.0, 1.0
        self.hover = self.focused = False
        self.drag = None
        self.bind('<Configure>', self.paint)
        self.bind('<Enter>', lambda e: self.highlight(True))
        self.bind('<Leave>', lambda e: self.highlight(False))
        self.bind('<FocusIn>', lambda e: self.focus(True))
        self.bind('<FocusOut>', lambda e: self.focus(False))
        self.bind('<Button-1>', self.press)
        self.bind('<B1-Motion>', self.motion)
        self.bind('<ButtonRelease-1>', self.release)
        self.bind('<MouseWheel>', self.wheel)
        for key, amount, units in [('Up', -1, 'units'), ('Down', 1, 'units'),
                                   ('Prior', -1, 'pages'), ('Next', 1, 'pages')]:
            self.bind('<'+key+'>', lambda e, n=amount, u=units: self.scroll(n, u))
        self.bind('<Home>', lambda e: self.move(0))
        self.bind('<End>', lambda e: self.move(1))

    def set(self, first, last):
        self.first = max(0.0, min(1.0, float(first)))
        self.last = max(self.first, min(1.0, float(last)))
        self.configure(takefocus=int(self.last-self.first < 1))
        self.paint()

    def geometry(self):
        height = max(1, self.winfo_height()-4)
        span = self.last-self.first
        size = min(height, max(28, height*span))
        travel = height-size
        top = 2 + (travel*self.first/(1-span) if span < 1 else 0)
        return top, size, travel

    def paint(self, event=None):
        self.delete('all')
        if self.last-self.first >= 1:
            return
        top, size, _ = self.geometry()
        active = self.hover or self.focused or self.drag is not None
        color = '#73e2bb' if self.drag is not None else '#6caa92' if active else '#435b50'
        width = 8 if active else 6
        radius = min(width/2, size/2)
        center = self.winfo_width()/2
        self.create_line(center, top+radius, center, top+size-radius,
                         fill=color, width=width, capstyle='round')

    def highlight(self, value):
        self.hover = value
        self.paint()

    def focus(self, value):
        self.focused = value
        self.paint()

    def press(self, event):
        if self.last-self.first >= 1:
            return
        self.focus_set()
        top, size, _ = self.geometry()
        if top <= event.y <= top+size:
            self.drag = (event.y, self.first)
        else:
            self.scroll(-1 if event.y < top else 1, 'pages')
        self.paint()

    def motion(self, event):
        if self.drag is not None:
            _, _, travel = self.geometry()
            if travel:
                self.move(self.drag[1]+(event.y-self.drag[0])*(1-self.last+self.first)/travel)

    def release(self, event):
        self.drag = None
        self.paint()

    def move(self, fraction):
        self.command('moveto', max(0, min(1-self.last+self.first, fraction)))
        return 'break'

    def scroll(self, amount, units='units'):
        self.command('scroll', amount, units)
        return 'break'

    def wheel(self, event):
        return self.scroll(-int(event.delta/120) or (-1 if event.delta > 0 else 1)) if event.delta else 'break'
