package technology.tabula;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix; 
import org.apache.pdfbox.contentstream.operator.OperatorProcessor; 
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;



import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.awt.geom.Point2D; 
import java.util.Collections;


public class TextStripper extends PDFTextStripper {
    private static final String NBSP = "\u00A0";
    private PDDocument document;
    public ArrayList<TextElement> textElements;
    public RectangleSpatialIndex<TextElement> spatialIndex;
    public float minCharWidth = Float.MAX_VALUE;
    public float minCharHeight = Float.MAX_VALUE;
    final List<TransformedRectangle> rectangles = new ArrayList<>();
    Set<String> currentStyle = Collections.singleton("Undefined");

    Boolean checkLineThrough;
    Boolean checkRed;

    public TextStripper(PDDocument document, int pageNumber,Boolean checkLineThrough,Boolean checkRed) throws IOException {
        super();
        this.document = document;
        this.setStartPage(pageNumber);
        this.setEndPage(pageNumber);
        this.textElements = new ArrayList<>();
        this.spatialIndex = new RectangleSpatialIndex<>();
        this.checkLineThrough=checkLineThrough;
        this.checkRed=checkRed;

        registerOperatorProcessor("re", new AppendRectangleToPath());
    }

    public void process() throws IOException {
        this.getText(this.document);
    }

    @Override
    protected void writeString(String string, List<TextPosition> textPositions) throws IOException
    {
    	boolean check_line_through = true;
    	boolean check_red = false;

        for (TextPosition textPosition: textPositions)
        {
            if (textPosition == null) {
                continue;
            }


            String c = textPosition.getUnicode();
            //modifications for openpowerlifting


            Boolean hasLineThrough= ((checkLineThrough && rectangles.stream().anyMatch(r -> r.strikesThrough(textPosition)))? true: false);
 			Boolean hasRed = (checkRed ? true : false);


            // if c not printable, return
            if (!isPrintable(c)) {
                continue;
            }

            Float h = textPosition.getHeightDir();

            if (c.equals(NBSP)) { // replace non-breaking space for space
                c = " ";
            }

            float wos = textPosition.getWidthOfSpace();

            TextElement te = new TextElement(Utils.round(textPosition.getYDirAdj() - h, 2),
                    Utils.round(textPosition.getXDirAdj(), 2), Utils.round(textPosition.getWidthDirAdj(), 2),
                    Utils.round(textPosition.getHeightDir(), 2), textPosition.getFont(), textPosition.getFontSize(), c
                    ,hasLineThrough,hasRed, textPosition.getDir(),
                    // workaround a possible bug in PDFBox:
                    // https://issues.apache.org/jira/browse/PDFBOX-1755
                    wos);

            this.minCharWidth = (float) Math.min(this.minCharWidth, te.getWidth());
            this.minCharHeight = (float) Math.min(this.minCharHeight, te.getHeight());

            this.spatialIndex.add(te);
            this.textElements.add(te);
        }
    }

    private boolean isPrintable(String s) {
        Character c;
        Character.UnicodeBlock block;
        boolean printable = false;
        for (int i = 0; i < s.length(); i++) {
            c = s.charAt(i);
            block = Character.UnicodeBlock.of(c);
            printable |= !Character.isISOControl(c) && block != null && block != Character.UnicodeBlock.SPECIALS;
        }
        return printable;
    }




    class AppendRectangleToPath extends OperatorProcessor
    {
        public void process(Operator operator, List<COSBase> arguments)
        {
            COSNumber x = (COSNumber) arguments.get(0);
            COSNumber y = (COSNumber) arguments.get(1);
            COSNumber w = (COSNumber) arguments.get(2);
            COSNumber h = (COSNumber) arguments.get(3);

            double x1 = x.doubleValue();
            double y1 = y.doubleValue();

            // create a pair of coordinates for the transformation
            double x2 = w.doubleValue() + x1;
            double y2 = h.doubleValue() + y1;

            Point2D p0 = transformedPoint(x1, y1);
            Point2D p1 = transformedPoint(x2, y1);
            Point2D p2 = transformedPoint(x2, y2);
            Point2D p3 = transformedPoint(x1, y2);

            rectangles.add(new TransformedRectangle(p0, p1, p2, p3));
        }

        Point2D.Double transformedPoint(double x, double y)
        {
            double[] position = {x,y}; 
            getGraphicsState().getCurrentTransformationMatrix().createAffineTransform().transform(
                    position, 0, position, 0, 1);
            return new Point2D.Double(position[0],position[1]);
        }

        public String getName()
        {
        	return "AppendRectangleToPath";
        }
    }

	static class TransformedRectangle {
		final Point2D p0, p1, p2, p3;
        public TransformedRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3)
        {
            this.p0 = p0;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
        }

        boolean strikesThrough(TextPosition textPosition)
        {
            Matrix matrix = textPosition.getTextMatrix();
            // TODO: This is a very simplistic implementation only working for horizontal text without page rotation
            // and horizontal rectangular strikeThroughs with p0 at the left bottom and p2 at the right top

            // Check if rectangle horizontally matches (at least) the text
            if (p0.getX() > matrix.getXPosition() || p2.getX() < matrix.getXPosition() + textPosition.getWidth() - textPosition.getFontSizeInPt() / 10.0)
                return false;
            // Check whether rectangle vertically is at the right height to underline
            double vertDiff = p0.getY() - matrix.getYPosition();
            if (vertDiff < 0 || vertDiff > textPosition.getFont().getFontDescriptor().getAscent() * textPosition.getFontSizeInPt() / 1000.0)
                return false;
            // Check whether rectangle is small enough to be a line
            return Math.abs(p2.getY() - p0.getY()) < 2;
        }

        boolean underlines(TextPosition textPosition)
        {
            Matrix matrix = textPosition.getTextMatrix();
            // TODO: This is a very simplistic implementation only working for horizontal text without page rotation
            // and horizontal rectangular underlines with p0 at the left bottom and p2 at the right top

            // Check if rectangle horizontally matches (at least) the text
            if (p0.getX() > matrix.getXPosition() || p2.getX() < matrix.getXPosition() + textPosition.getWidth() - textPosition.getFontSizeInPt() / 10.0)
                return false;
            // Check whether rectangle vertically is at the right height to underline
            double vertDiff = p0.getY() - matrix.getYPosition();
            if (vertDiff > 0 || vertDiff < textPosition.getFont().getFontDescriptor().getDescent() * textPosition.getFontSizeInPt() / 500.0)
                return false;
            // Check whether rectangle is small enough to be a line
            return Math.abs(p2.getY() - p0.getY()) < 2;
        }

        
    }


}