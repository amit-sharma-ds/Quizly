import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.stream.*;
import java.util.regex.*;
class TextProcessor {
    static List<String> chunk(String text) {
        List<String> raw = new ArrayList<>();
        for (String s : text.split("[.!?\n]+")) {
            s = s.trim();
            if (s.length() > 20) raw.add(s);
        }
        return raw;
    }
    static Map<String, Integer> termFreq(String text) {
        Map<String, Integer> freq = new HashMap<>();
        Set<String> stop = new HashSet<>(Arrays.asList(
            "the","a","an","is","are","was","were","in","on","at","to",
            "of","and","or","but","it","its","this","that","with","for",
            "as","by","from","be","has","have","had","not","can","do"
        ));
        for (String w : text.toLowerCase().split("\\W+"))
            if (w.length() > 2 && !stop.contains(w))
                freq.merge(w, 1, Integer::sum);
        return freq;
    }
    static String blankify(String sentence) {
        String[] words = sentence.split("\\s+");
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            String w = words[i].replaceAll("\\W","");
            if (w.length() > 4) candidates.add(i);
        }
        if (candidates.isEmpty()) return null;
        int pick = candidates.get(new Random().nextInt(candidates.size()));
        String ans = words[pick].replaceAll("\\W","");
        words[pick] = "_____";
        return ans + "||" + String.join(" ", words);
    }
    static String distractor(String answer, Map<String,Integer> freq) {
        List<String> pool = new ArrayList<>(freq.keySet());
        pool.removeIf(w -> w.equalsIgnoreCase(answer) || w.length() < 3);
        Collections.shuffle(pool);
        return pool.isEmpty() ? "none" : capitalize(pool.get(0));
    }
    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
class RetrievalEngine {
    private final List<String> chunks;
    private final Map<String, Integer> globalFreq;
    RetrievalEngine(List<String> chunks, String fullText) {
        this.chunks = chunks;
        this.globalFreq = TextProcessor.termFreq(fullText);
    }
    int score(String chunk) {
        int s = 0;
        Map<String,Integer> cf = TextProcessor.termFreq(chunk);
        for (Map.Entry<String,Integer> e : cf.entrySet()) {
            int gf = globalFreq.getOrDefault(e.getKey(), 0);
            s += e.getValue() * (1 + gf / 2);
        }
        return s;
    }
    List<String> topChunks(int n, int diffLevel) {
        List<String> sorted = new ArrayList<>(chunks);
        sorted.sort((a, b) -> score(b) - score(a));
        List<String> filtered;
        if (diffLevel == 1)
            filtered = sorted.stream().filter(c -> c.split("\\s+").length <= 15).collect(Collectors.toList());
        else if (diffLevel == 2)
            filtered = sorted.stream().filter(c -> c.split("\\s+").length > 8).collect(Collectors.toList());
        else
            filtered = sorted.stream().filter(c -> c.split("\\s+").length > 14).collect(Collectors.toList());
        if (filtered.size() < n) filtered.addAll(sorted);
        return filtered.stream().distinct().limit(n).collect(Collectors.toList());
    }
    Map<String,Integer> getGlobalFreq() { return globalFreq; }
}
class Question {
    enum Type { MCQ, TRUE_FALSE, FILL_BLANK }
    String prompt, correctAnswer, chunk;
    String[] options;
    Type type;
    Question(String prompt, String correct, String[] opts, Type t, String chunk) {
        this.prompt = prompt; this.correctAnswer = correct;
        this.options = opts; this.type = t; this.chunk = chunk;
    }
}
class QuizEngine {
    final List<Question> questions = new ArrayList<>();
    private int index = 0, score = 0, difficulty = 1;
    private final List<String> wrongChunks = new ArrayList<>();
    private final Map<String,Integer> freq;
    QuizEngine(RetrievalEngine re, int count) {
        freq = re.getGlobalFreq();
        List<String> pool = re.topChunks(count * 3, difficulty);
        Collections.shuffle(pool);
        int made = 0;
        for (String chunk : pool) {
            if (made >= count) break;
            Question q = generate(chunk);
            if (q != null) { questions.add(q); made++; }
        }
    }
    Question generate(String chunk) {
        String[] words = chunk.split("\\s+");
        double rand = Math.random();
        if (rand < 0.35) {
            String prompt = "What best describes the following:\n\"" + truncate(chunk, 90) + "\"";
            List<String> distractors = buildDistractors(chunk, 3);
            if (distractors.size() < 3) return makeTF(chunk);
            String correct = TextProcessor.capitalize(TextProcessor.termFreq(chunk)
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("concept"));
            distractors.add(0, correct);
            Collections.shuffle(distractors);
            return new Question(prompt, correct, distractors.toArray(new String[0]), Question.Type.MCQ, chunk);
        } else if (rand < 0.65 && words.length > 6) {
            String result = TextProcessor.blankify(chunk);
            if (result == null) return makeTF(chunk);
            String[] parts = result.split("\\|\\|");
            String answer = TextProcessor.capitalize(parts[0]);
            String prompt = "Fill in the blank:\n\"" + parts[1] + "\"";
            List<String> opts = buildDistractors(chunk, 3);
            opts.add(0, answer);
            while (opts.size() < 4) opts.add("None of the above");
            opts = opts.stream().distinct().limit(4).collect(Collectors.toList());
            Collections.shuffle(opts);
            return new Question(prompt, answer, opts.toArray(new String[0]), Question.Type.FILL_BLANK, chunk);
        } else {
            return makeTF(chunk);
        }
    }
    Question makeTF(String chunk) {
        boolean truth = Math.random() > 0.45;
        String prompt = "True or False:\n\"" + truncate(chunk, 100) + "\"";
        String correct = truth ? "True" : "False";
        if (!truth) prompt = "True or False:\n\"" + mutate(chunk) + "\"";
        return new Question(prompt, correct, new String[]{"True","False"}, Question.Type.TRUE_FALSE, chunk);
    }
    String mutate(String chunk) {
        Map<String,String> swaps = new LinkedHashMap<>();
        swaps.put("increases","decreases"); swaps.put("decreases","increases");
        swaps.put("causes","prevents"); swaps.put("prevents","causes");
        swaps.put("positive","negative"); swaps.put("negative","positive");
        swaps.put("large","small"); swaps.put("small","large");
        swaps.put("fast","slow"); swaps.put("slow","fast");
        swaps.put("high","low"); swaps.put("low","high");
        swaps.put("always","never"); swaps.put("never","always");
        for (Map.Entry<String,String> e : swaps.entrySet()) {
            if (chunk.toLowerCase().contains(e.getKey())) {
                return chunk.replaceAll("(?i)\\b" + e.getKey() + "\\b", e.getValue());
            }
        }
        return chunk.replaceFirst("(is|are|was|were|has|have)\\s", "$1 NOT ");
    }
    List<String> buildDistractors(String chunk, int n) {
        List<String> pool = freq.entrySet().stream()
            .filter(e -> e.getValue() > 1 && e.getKey().length() > 3)
            .sorted((a,b) -> b.getValue()-a.getValue())
            .map(e -> TextProcessor.capitalize(e.getKey()))
            .collect(Collectors.toList());
        Set<String> chunkWords = new HashSet<>(Arrays.asList(chunk.toLowerCase().split("\\W+")));
        pool.removeIf(w -> chunkWords.contains(w.toLowerCase()));
        Collections.shuffle(pool);
        return pool.stream().limit(n).collect(Collectors.toList());
    }
    String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "â€¦";
    }
    boolean hasNext() { return index < questions.size(); }
    Question current() { return questions.get(index); }
    int total() { return questions.size(); }
    int index() { return index; }
    int score() { return score; }
    List<String> wrongChunks() { return wrongChunks; }
    boolean answer(String chosen) {
        Question q = current();
        boolean ok = chosen.trim().equalsIgnoreCase(q.correctAnswer.trim());
        if (ok) { score++; difficulty = Math.min(3, difficulty + 1); }
        else    { wrongChunks.add(q.chunk); difficulty = Math.max(1, difficulty - 1); }
        index++;
        return ok;
    }
}
class NeonPanel extends JPanel {
    private Color accent; private String label; NeonPanel(Color a){accent=a;setOpaque(false);} NeonPanel(Color a,String l){this(a);label=l;}
    @Override protected void paintComponent(Graphics g){Graphics2D g2=(Graphics2D)g.create();g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int w=getWidth(),h=getHeight();
        g2.setColor(new Color(12,12,28,200));g2.fillRoundRect(0,0,w,h,18,18);g2.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),60));g2.setStroke(new BasicStroke(1.5f));g2.drawRoundRect(1,1,w-2,h-2,18,18);
        g2.setColor(new Color(255,255,255,8));g2.fillRoundRect(2,2,w-4,6,10,10);if(label!=null){g2.setFont(new Font("SansSerif",Font.BOLD,11));g2.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),160));g2.drawString(label,14,18);}g2.dispose();super.paintComponent(g);}
}
class ParticleCanvas extends JPanel {
    float[][] px,py,ps; Color[] pc; Random rng=new Random(); ParticleCanvas(int n){setOpaque(false);px=new float[n][1];py=new float[n][1];ps=new float[n][1];pc=new Color[n];Color[] cols={new Color(0,220,255),new Color(140,60,255),new Color(255,60,180)};for(int i=0;i<n;i++){px[i][0]=rng.nextFloat()*900;py[i][0]=rng.nextFloat()*620;ps[i][0]=0.2f+rng.nextFloat()*0.6f;pc[i]=cols[i%3];}}
    void tick(){for(int i=0;i<px.length;i++){py[i][0]-=ps[i][0];if(py[i][0]<-4){py[i][0]=624;px[i][0]=rng.nextFloat()*900;}}repaint();}
    @Override protected void paintComponent(Graphics g){Graphics2D g2=(Graphics2D)g.create();g2.setColor(new Color(8,8,20));g2.fillRect(0,0,getWidth(),getHeight());g2.setColor(new Color(35,35,70,25));g2.setStroke(new BasicStroke(.5f));
        for(int x=0;x<900;x+=50)g2.drawLine(x,0,x,620);for(int y=0;y<620;y+=50)g2.drawLine(0,y,900,y);for(int i=0;i<px.length;i++){Color c=pc[i];int a=(int)(35+ps[i][0]*80);g2.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),a));g2.fillOval((int)px[i][0],(int)py[i][0],(int)(1+ps[i][0]*2),(int)(1+ps[i][0]*2));}g2.dispose();}
}
class GlowButton extends JButton {
    private Color base,hover; private float glow=0; private javax.swing.Timer gt;
    GlowButton(String txt,Color b,Color h){super(txt);base=b;hover=h;setContentAreaFilled(false);setBorderPainted(false);setFocusPainted(false);setFont(new Font("SansSerif",Font.BOLD,14));setForeground(Color.WHITE);setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));addMouseListener(new MouseAdapter(){public void mouseEntered(MouseEvent e){startGlow(true);}public void mouseExited(MouseEvent e){startGlow(false);}});}
    void startGlow(boolean in){if(gt!=null)gt.stop();gt=new javax.swing.Timer(16,e->{glow=in?Math.min(1f,glow+.08f):Math.max(0f,glow-.08f);if((in&&glow>=1)||(!in&&glow<=0))gt.stop();repaint();});gt.start();}
    @Override protected void paintComponent(Graphics g){Graphics2D g2=(Graphics2D)g.create();g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int w=getWidth(),h=getHeight();Color c=blend(base,hover,glow);
        if(glow>0)for(int i=(int)(20*glow);i>0;i-=4){g2.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),(int)(15*glow)));g2.fillRoundRect(-i,-i,w+i*2,h+i*2,16,16);}g2.setPaint(new GradientPaint(0,0,c.darker(),w,h,c));g2.fillRoundRect(0,0,w,h,12,12);g2.setColor(new Color(255,255,255,25));g2.fillRoundRect(0,0,w,h/2,12,12);g2.dispose();super.paintComponent(g);}
    Color blend(Color a,Color b,float t){return new Color((int)(a.getRed()+t*(b.getRed()-a.getRed())),(int)(a.getGreen()+t*(b.getGreen()-a.getGreen())),(int)(a.getBlue()+t*(b.getBlue()-a.getBlue())));}
}
class UIController extends JFrame {
    static final Color BG=new Color(8,8,20), CYAN=new Color(0,220,255),
        PURPLE=new Color(140,60,255), PINK=new Color(255,60,180),
        TEXT=new Color(210,210,255), MUTED=new Color(100,100,150),
        CORRECT=new Color(0,255,160), WRONG=new Color(255,60,80);
    JLayeredPane layers; ParticleCanvas particles; JPanel cardHolder;
    CardLayout cards = new CardLayout();
    QuizEngine engine; String inputText="";
    javax.swing.Timer pTimer;
    UIController(){
        super("Quizly â€” Code Olympics 2026");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(900,620); setLocationRelativeTo(null); setResizable(false);
        layers = new JLayeredPane(); layers.setPreferredSize(new Dimension(900,620));
        particles = new ParticleCanvas(55); particles.setBounds(0,0,900,620);
        layers.add(particles, Integer.valueOf(0));
        cardHolder = new JPanel(cards); cardHolder.setOpaque(false); cardHolder.setBounds(0,0,900,620);
        layers.add(cardHolder, Integer.valueOf(1));
        cardHolder.add(buildHome(), "HOME");
        cardHolder.add(buildPlaceholder(), "QUIZ");
        cardHolder.add(buildPlaceholder(), "RESULTS");
        add(layers);
        pTimer = new javax.swing.Timer(16, e->particles.tick()); pTimer.start();
        cards.show(cardHolder,"HOME");
    }
    JPanel buildPlaceholder(){ JPanel p=new JPanel(); p.setOpaque(false); return p; }
    JPanel buildHome(){
        JPanel root=new JPanel(null); root.setOpaque(false);
        JLabel title=new JLabel("Quizly", SwingConstants.CENTER){
            float t=0; { new javax.swing.Timer(40,e->{t+=.05f;repaint();}).start(); }
            @Override protected void paintComponent(Graphics g){
                Graphics2D g2=(Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                int gv=(int)(180+Math.sin(t)*55);
                Color c=new Color(0,gv,255);
                g2.setFont(new Font("SansSerif",Font.BOLD,72));
                FontMetrics fm=g2.getFontMetrics(); int tx=(getWidth()-fm.stringWidth("Quizly"))/2;
                for(int i=12;i>0;i-=2){g2.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),(int)(15-i)));g2.drawString("Quizly",tx+i/3,70+i/3);g2.drawString("Quizly",tx-i/3,70-i/3);}
                g2.setColor(c); g2.drawString("Quizly",tx,70);
                g2.setColor(Color.WHITE.darker()); g2.drawString("Quizly",tx,70);
                g2.dispose();
            }
        };
        title.setBounds(0,30,900,85);
        JLabel sub=styledLabel("DOCUMENT-DRIVEN QUIZ GENERATOR  Â·  CODE OLYMPICS 2026", 12, MUTED);
        sub.setHorizontalAlignment(SwingConstants.CENTER); sub.setBounds(0,110,900,20);
        JLabel hint=styledLabel("Paste notes/article text or upload a text-based PDF below:", 13, new Color(150,150,200));
        hint.setBounds(120,148,500,22);
        GlowButton pdfBtn=new GlowButton("UPLOAD PDF", new Color(90,30,150), PINK);
        pdfBtn.setBounds(640,142,140,32); pdfBtn.setFont(new Font("SansSerif",Font.BOLD,12));
        JTextArea ta=new JTextArea();
        ta.setFont(new Font("Monospaced",Font.PLAIN,13)); ta.setForeground(TEXT);
        ta.setBackground(new Color(10,10,25)); ta.setCaretColor(CYAN);
        ta.setLineWrap(true); ta.setWrapStyleWord(true);
        ta.setBorder(BorderFactory.createEmptyBorder(12,14,12,14));
        ta.setText("Artificial intelligence (AI) is the simulation of human intelligence processes by computer systems. Machine learning is a subset of AI that allows computers to learn from data without being explicitly programmed. Deep learning uses neural networks with many layers to analyze data. Natural language processing enables computers to understand and generate human language. Computer vision allows machines to interpret and make decisions based on visual data from the world.");
        JScrollPane scroll=new JScrollPane(ta);
        scroll.setBounds(120,176,660,240);
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60,60,120),1,true),
            BorderFactory.createEmptyBorder(0,0,0,0)));
        JLabel countLbl=styledLabel("Questions: 8", 12, CYAN); countLbl.setBounds(340,430,220,20);
        JSlider slider=new JSlider(4,20,8); slider.setOpaque(false);
        slider.setForeground(CYAN); slider.setBounds(120,452,300,30);
        slider.addChangeListener(e->countLbl.setText("Questions: "+slider.getValue()));
        JLabel diffLbl=styledLabel("Mode: Auto-Adaptive", 12, PURPLE); diffLbl.setBounds(480,452,300,30);
        GlowButton btn=new GlowButton("GENERATE QUIZ â†’", new Color(0,120,200), CYAN);
        btn.setBounds(325,500,250,48); btn.setFont(new Font("SansSerif",Font.BOLD,15));
        btn.addActionListener(e->{
            String txt=ta.getText().trim();
            if(txt.length()<50){showError(root,"Please paste at least a few sentences of text."); return;}
            inputText=txt; startQuiz(slider.getValue());
        });
        pdfBtn.addActionListener(e->{
            JFileChooser fc=new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF files","pdf"));
            if(fc.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
            try{
                String text=extractPdfText(fc.getSelectedFile()).trim();
                if(text.length()<50){showError(root,"Could not read enough text from this PDF.");return;}
                ta.setText(text); ta.setCaretPosition(0);
            }catch(Exception ex){showError(root,"PDF read failed. Use a text-based PDF.");}
        });
        JLabel tagline=styledLabel("Powered by Rule-Based Pseudo-RAG Engine", 11, new Color(70,70,110));
        tagline.setHorizontalAlignment(SwingConstants.CENTER); tagline.setBounds(0,556,900,20);
        root.add(title); root.add(sub); root.add(hint); root.add(pdfBtn); root.add(scroll);
        root.add(countLbl); root.add(slider); root.add(diffLbl); root.add(btn); root.add(tagline);
        return root;
    }
    String extractPdfText(File file) throws IOException{
        byte[] bytes=Files.readAllBytes(file.toPath());
        String raw=new String(bytes, StandardCharsets.ISO_8859_1);
        StringBuilder out=new StringBuilder();
        Matcher m=Pattern.compile("\\((?:\\\\.|[^\\\\)])*\\)").matcher(raw);
        while(m.find()){
            String s=m.group();
            s=s.substring(1,s.length()-1).replace("\\n","\n").replace("\\r","\r").replace("\\t","\t")
                .replace("\\(","(").replace("\\)",")").replace("\\\\","\\");
            if(readablePdfText(s))out.append(s).append(' ');
        }
        if(out.length()<50){
            String cleaned=raw.replaceAll("[^\\x20-\\x7E\\n\\r\\t]+"," ").replaceAll("\\s+"," ");
            Matcher words=Pattern.compile("([A-Za-z][A-Za-z0-9,.;:'\"()\\- ]{40,})").matcher(cleaned);
            while(words.find()&&out.length()<8000){String s=words.group(1);if(readablePdfText(s))out.append(s).append(' ');}
        }
        return out.toString().replaceAll("\\s+"," ");
    }
    boolean readablePdfText(String s){int ascii=0,letters=0;for(char c:s.toCharArray()){if(("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,;:'\"()-").indexOf(c)>=0)ascii++;if(Character.isLetter(c)&&c<128)letters++;}return s.length()>8&&letters>3&&ascii/(double)Math.max(1,s.length())>.75;}
    void showError(JPanel p, String msg){
        JLabel err=styledLabel("âš   "+msg, 12, WRONG); err.setBounds(200,548,500,20); err.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(err); p.repaint();
        new javax.swing.Timer(3000,e->{p.remove(err);p.repaint();((javax.swing.Timer)e.getSource()).stop();}).start();
    }
    void startQuiz(int count){
        List<String> chunks=TextProcessor.chunk(inputText);
        if(chunks.isEmpty()){return;}
        RetrievalEngine re=new RetrievalEngine(chunks,inputText);
        engine=new QuizEngine(re,Math.min(count,chunks.size()*2));
        if(!engine.hasNext()) return;
        cardHolder.remove(cardHolder.getComponent(1));
        cardHolder.add(buildQuizPanel(),  "QUIZ", 1);
        cards.show(cardHolder,"QUIZ");
    }
    JPanel buildQuizPanel(){ return renderQuestion(); }
    JPanel renderQuestion(){
        JPanel root=new JPanel(null); root.setOpaque(false);
        Question q=engine.current(); int idx=engine.index(), total=engine.total();
        NeonPanel header=new NeonPanel(CYAN); header.setBounds(25,18,850,52); header.setLayout(null);
        JLabel brand=styledLabel("QUIZLY", 13, CYAN); brand.setBounds(16,15,80,22); header.add(brand);
        JLabel catLbl=styledLabel("DOCUMENT QUIZ", 11, MUTED); catLbl.setBounds(110,18,200,18); header.add(catLbl);
        String diff=engine.score()>(idx*.7)?"âš¡ HARD":engine.score()>(idx*.4)?"â—ˆ MEDIUM":"â—Ž EASY";
        Color dc=engine.score()>(idx*.7)?WRONG:engine.score()>(idx*.4)?new Color(255,200,0):CORRECT;
        JLabel dLbl=styledLabel(diff,11,dc); dLbl.setBounds(700,18,90,18); header.add(dLbl);
        JLabel scLbl=styledLabel("SCORE: "+engine.score(),13,TEXT); scLbl.setBounds(790,15,55,22); header.add(scLbl);
        root.add(header);
        JPanel pb=new JPanel(null); pb.setOpaque(false); pb.setBounds(25,78,850,8);
        JPanel pbBg=new JPanel(); pbBg.setBounds(0,0,850,8); pbBg.setBackground(new Color(25,25,55)); pbBg.setBorder(new RoundedBorder(8)); root.add(pb);
        int pw=(int)(850.0*(idx+1)/total);
        JPanel pbFg=new JPanel(){ @Override protected void paintComponent(Graphics g){Graphics2D g2=(Graphics2D)g.create();GradientPaint gp=new GradientPaint(0,0,PURPLE,pw,0,CYAN);g2.setPaint(gp);g2.fillRoundRect(0,0,getWidth(),8,8,8);g2.dispose();}};
        pbFg.setBounds(0,0,pw,8); pbFg.setOpaque(false);
        pb.add(pbBg); pb.add(pbFg);
        JLabel qnum=styledLabel("Q "+(idx+1)+" of "+total, 11, MUTED); qnum.setBounds(25,92,200,18); root.add(qnum);
        String typeStr=q.type==Question.Type.MCQ?"MULTIPLE CHOICE":q.type==Question.Type.TRUE_FALSE?"TRUE / FALSE":"FILL IN THE BLANK";
        JLabel qlbl=styledLabel("[ "+typeStr+" ]", 10, PURPLE); qlbl.setBounds(700,92,200,18); qlbl.setHorizontalAlignment(SwingConstants.RIGHT); root.add(qlbl);
        NeonPanel qcard=new NeonPanel(PURPLE,"SOURCE CONTEXT"); qcard.setBounds(60,116,780,130); qcard.setLayout(null);
        JTextArea qtxt=new JTextArea(q.prompt); qtxt.setFont(new Font("SansSerif",Font.PLAIN,16));
        qtxt.setForeground(TEXT); qtxt.setOpaque(false); qtxt.setEditable(false); qtxt.setLineWrap(true); qtxt.setWrapStyleWord(true);
        qtxt.setBorder(BorderFactory.createEmptyBorder(24,18,8,18)); qtxt.setBounds(0,0,780,130); qcard.add(qtxt); root.add(qcard);
        int cols=q.options.length==2?1:2;
        int bw=cols==1?500:360, bx0=cols==1?200:60;
        for(int i=0;i<q.options.length;i++){
            int col=i%cols, row=i/cols;
            int bx=bx0+col*(bw+20), by=264+row*72;
            GlowButton ab=new GlowButton(q.options[i], new Color(18,18,46), PURPLE);
            ab.setBounds(bx,by,bw,56); ab.setFont(new Font("SansSerif",Font.PLAIN,14));
            final String ans=q.options[i]; final JPanel fr=root;
            ab.addActionListener(e->handleAnswer(ans, fr));
            root.add(ab);
        }
        NeonPanel srcPanel=new NeonPanel(new Color(40,40,80),"RETRIEVED CHUNK");
        srcPanel.setBounds(60,456,780,60); srcPanel.setLayout(null);
        String src=engine.current().chunk; if(src.length()>140) src=src.substring(0,140)+"â€¦";
        JLabel srcLbl=styledLabel(src, 11, new Color(80,80,120)); srcLbl.setBounds(14,20,752,30);
        srcLbl.setHorizontalAlignment(SwingConstants.LEFT); srcPanel.add(srcLbl); root.add(srcPanel);
        JLabel tagline=styledLabel("RAG-Inspired Â· Rule-Based Â· Adaptive", 11, new Color(50,50,80));
        tagline.setHorizontalAlignment(SwingConstants.CENTER); tagline.setBounds(0,530,900,20); root.add(tagline);
        return root;
    }
    void handleAnswer(String chosen, JPanel panel){
        boolean ok=engine.answer(chosen);
        JPanel overlay=new JPanel(null); overlay.setOpaque(false); overlay.setBounds(0,0,900,620);
        JLabel fl=new JLabel(ok?"âœ“  CORRECT!":"âœ—  WRONG", SwingConstants.CENTER);
        fl.setFont(new Font("SansSerif",Font.BOLD,52));
        fl.setForeground(ok?CORRECT:WRONG); fl.setBounds(0,210,900,80);
        overlay.add(fl);
        if(!ok){
            Question prev=engine.questions.get(engine.index()-1);
            JLabel hint=styledLabel("Answer: "+prev.correctAnswer, 15, new Color(200,200,255));
            hint.setHorizontalAlignment(SwingConstants.CENTER); hint.setBounds(0,295,900,26); overlay.add(hint);
        }
        panel.add(overlay); panel.setComponentZOrder(overlay,0); panel.repaint();
        new javax.swing.Timer(1400,e->{
            ((javax.swing.Timer)e.getSource()).stop();
            panel.remove(overlay);
            if(engine.hasNext()){
                cardHolder.remove(cardHolder.getComponent(1));
                cardHolder.add(renderQuestion(),"QUIZ",1);
                cards.show(cardHolder,"QUIZ");
            } else {
                showResults();
            }
        }).start();
    }
    void showResults(){
        cardHolder.remove(2);
        cardHolder.add(buildResults(),"RESULTS",2);
        cards.show(cardHolder,"RESULTS");
    }
    JPanel buildResults(){
        int total=engine.total(), sc=engine.score();
        float pct=(float)sc/total;
        String rank=pct>=.85?"ðŸ†  EXPERT":pct>=.55?"âš¡  INTERMEDIATE":"ðŸŒ±  BEGINNER";
        Color rc=pct>=.85?CORRECT:pct>=.55?CYAN:PINK;
        JPanel root=new JPanel(null); root.setOpaque(false);
        JLabel title=styledLabel("QUIZ COMPLETE", 32, TEXT);
        title.setFont(new Font("SansSerif",Font.BOLD,32)); title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setBounds(0,30,900,42); root.add(title);
        JLabel sub=styledLabel("Your document quiz results", 13, MUTED);
        sub.setHorizontalAlignment(SwingConstants.CENTER); sub.setBounds(0,76,900,20); root.add(sub);
        JPanel circle=new JPanel(){float anim=0;int[] disp={0};javax.swing.Timer at;{setOpaque(false);at=new javax.swing.Timer(18,e->{anim=Math.min(pct,anim+.015f);disp[0]=Math.min(sc,pct==0?0:(int)(sc*(anim/pct)));if(anim>=pct)at.stop();repaint();});at.start();}
            @Override protected void paintComponent(Graphics g){Graphics2D g2=(Graphics2D)g.create();g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int cx=90,cy=90,r=78;g2.setStroke(new BasicStroke(14,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g2.setColor(new Color(25,25,55));g2.drawOval(cx-r,cy-r,r*2,r*2);g2.setColor(rc);g2.drawArc(cx-r,cy-r,r*2,r*2,90,-(int)(anim*360));g2.setFont(new Font("SansSerif",Font.BOLD,38));g2.setColor(TEXT);FontMetrics fm=g2.getFontMetrics();String s=disp[0]+"/"+total;g2.drawString(s,cx-fm.stringWidth(s)/2,cy+7);g2.setFont(new Font("SansSerif",Font.PLAIN,12));g2.setColor(MUTED);String ps=(int)(anim*100)+"% accuracy";g2.drawString(ps,cx-fm.stringWidth(ps)/2+8,cy+26);g2.dispose();}};
        circle.setBounds(360,108,180,180); root.add(circle);
        JPanel graph=new JPanel(){@Override protected void paintComponent(Graphics g){Graphics2D g2=(Graphics2D)g.create();g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int wrong=total-sc,cw=(int)(240*(sc/(double)Math.max(1,total))),ww=(int)(240*(wrong/(double)Math.max(1,total)));g2.setColor(CORRECT);g2.fillRoundRect(52,22,cw,18,10,10);g2.setColor(WRONG);g2.fillRoundRect(52,58,ww,18,10,10);g2.setFont(new Font("SansSerif",Font.PLAIN,12));g2.setColor(TEXT);g2.drawString("Correct",8,36);g2.drawString("Wrong",8,72);g2.drawString(sc+"",300,36);g2.drawString(wrong+"",300,72);g2.dispose();}};
        graph.setOpaque(false);graph.setBounds(585,160,300,88);root.add(graph);
        JLabel rankLbl=new JLabel(rank,SwingConstants.CENTER);
        rankLbl.setFont(new Font("SansSerif",Font.BOLD,26)); rankLbl.setForeground(rc);
        rankLbl.setBounds(0,300,900,36); root.add(rankLbl);
        NeonPanel stats=new NeonPanel(CYAN); stats.setBounds(120,348,300,110); stats.setLayout(null);
        stats.add(row("âœ“  Correct",  String.valueOf(sc),    CORRECT, 16));
        stats.add(row("âœ—  Wrong",    String.valueOf(total-sc), WRONG, 46));
        stats.add(row("Score",       sc+"Ã—10 = "+(sc*10)+" pts", CYAN, 76));
        NeonPanel weak=new NeonPanel(PINK,"WEAK AREAS (wrong chunks)"); weak.setBounds(460,348,340,110); weak.setLayout(null);
        List<String> wa=engine.wrongChunks();
        if(wa.isEmpty()){JLabel p=styledLabel("Perfect! No weak areas.",12,CORRECT);p.setBounds(12,38,316,20);weak.add(p);}
        else{for(int i=0;i<Math.min(2,wa.size());i++){String w=wa.get(i);if(w.length()>55)w=w.substring(0,55)+"â€¦";JLabel wl=styledLabel("â€¢ "+w,11,new Color(200,160,200));wl.setBounds(12,28+i*30,316,24);weak.add(wl);}}
        root.add(stats); root.add(weak);
        String fb=pct>=.85?"Outstanding! You mastered this document.":pct>=.55?"Good work! Review the highlighted chunks.":"Keep studying â€” try again for better results.";
        JLabel fbLbl=styledLabel("\""+fb+"\"", 14, new Color(160,160,200));
        fbLbl.setHorizontalAlignment(SwingConstants.CENTER); fbLbl.setBounds(80,472,740,24); root.add(fbLbl);
        GlowButton restart=new GlowButton("TRY AGAIN  â†º", new Color(150,30,100), PINK);
        restart.setBounds(290,510,160,46); restart.addActionListener(e->cards.show(cardHolder,"HOME")); root.add(restart);
        GlowButton newDoc=new GlowButton("NEW DOCUMENT  â†’", new Color(0,100,160), CYAN);
        newDoc.setBounds(460,510,190,46); newDoc.addActionListener(e->{
            cardHolder.remove(0); cardHolder.add(buildHome(),"HOME",0); cards.show(cardHolder,"HOME");
        }); root.add(newDoc);
        return root;
    }
    JPanel row(String lbl, String val, Color vc, int y){
        JPanel p=new JPanel(null); p.setOpaque(false); p.setBounds(0,y,300,28);
        JLabel l=styledLabel(lbl,12,MUTED); l.setBounds(16,4,140,20); p.add(l);
        JLabel v=styledLabel(val,13,vc); v.setBounds(180,4,104,20); p.add(v); return p;
    }
    JLabel styledLabel(String t, int sz, Color c){
        JLabel l=new JLabel(t); l.setFont(new Font("SansSerif",Font.PLAIN,sz)); l.setForeground(c); return l;
    }
}
class RoundedBorder extends AbstractBorder {
    int r; RoundedBorder(int r){this.r=r;}
    @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h){
        Graphics2D g2=(Graphics2D)g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(40,40,80)); g2.drawRoundRect(x,y,w-1,h-1,r,r); g2.dispose();
    }
}
public class Quizly2 {
    public static void main(String[] args){
        System.setProperty("awt.useSystemAAFontSettings","on");
        System.setProperty("swing.aatext","true");
        SwingUtilities.invokeLater(()->{
            UIManager.put("ScrollBarUI","javax.swing.plaf.basic.BasicScrollBarUI");
            UIController app=new UIController();
            app.setVisible(true);
        });
    }
}
