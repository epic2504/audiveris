//----------------------------------------------------------------------------//
//                                                                            //
//                         G l y p h s B u i l d e r                          //
//                                                                            //
//  Copyright (C) Herve Bitteur 2000-2007. All rights reserved.               //
//  This software is released under the GNU General Public License.           //
//  Contact author at herve.bitteur@laposte.net to report bugs & suggestions. //
//----------------------------------------------------------------------------//
//
package omr.glyph;

import omr.constant.ConstantSet;

import omr.lag.Section;

import omr.score.Staff;
import omr.score.SystemPoint;

import omr.sheet.Dash;
import omr.sheet.Scale;
import omr.sheet.Sheet;
import omr.sheet.SystemInfo;
import omr.sheet.SystemSplit;

import omr.stick.Stick;

import omr.util.Logger;

import java.awt.Rectangle;
import java.util.*;

/**
 * Class <code>GlyphsBuilder</code> is in charge of building (and removing)
 * glyphs and of updating accordingly the containing entities (GlyphLag and
 * SystemInfo). Though there are vertical and horizontal glyphs, a GlyphBuilder
 * is meant to handle only vertical glyphs, since it plays only with the sheet
 * vertical lag and with the system vertical sections.
 *
 * <p>It does not handle the shape of a glyph (this higher-level task is handled
 * by {@link GlyphInspector} among others). But it does handle all the physical
 * characteristics of a glyph via {@link #computeGlyphFeatures} (moments, plus
 * additional data such as ledger, stem).
 *
 * <p>It typically handles via {@link #retrieveGlyphs} the building of glyphs
 * out of the remaining sections of a sheet (since this is done using the
 * physical edges between the sections).
 *
 * <p>It provides the provisioning methods to actually insert or remove a glyph.<ul>
 *
 * <li>A given newly built glyph can be inserted via {@link #insertGlyph}
 *
 * <li>Similarly {@link #removeGlyph} allows the removal of an existing glyph.
 * <B>Nota:</B> Remember that the sections that compose a glyph are not removed,
 * only the glyph is removed. The link from the contained sections back to the
 * containing glyph is updated or not according to the proper method parameter.
 *
 * </ul>
 *
 * @author Herv&eacute; Bitteur
 * @version $Id$
 */
public class GlyphsBuilder
{
    //~ Static fields/initializers ---------------------------------------------

    /** Specific application parameters */
    private static final Constants constants = new Constants();

    /** Usual logger utility */
    private static final Logger logger = Logger.getLogger(GlyphsBuilder.class);

    //~ Instance fields --------------------------------------------------------

    /** The containing sheet */
    private final Sheet sheet;

    /** The global sheet scale */
    private final Scale scale;

    /** Lag of vertical runs */
    private final GlyphLag vLag;

    //~ Constructors -----------------------------------------------------------

    //---------------//
    // GlyphsBuilder //
    //---------------//
    /**
     * Creates a sheet-dedicated builder of glyphs
     *
     * @param sheet the contextual sheet
     */
    public GlyphsBuilder (Sheet sheet)
    {
        this.sheet = sheet;
        scale = sheet.getScale();

        // Reuse vertical lag (from bars step).
        vLag = sheet.getVerticalLag();
    }

    //~ Methods ----------------------------------------------------------------

    //---------------//
    // buildCompound //
    //---------------//
    /**
     * Make a new glyph out of a collection of (sub) glyphs, by merging all
     * their member sections. This compound is temporary, since until it is
     * properly inserted by use of {@link #insertGlyph}, this building has no
     * impact on either the containing lag, the containing system, nor the
     * contained sections themselves.
     *
     * @param parts the collection of (sub) glyphs
     * @return the brand new (compound) glyph
     */
    public Glyph buildCompound (Collection<Glyph> parts)
    {
        // Build a glyph from all sections
        Glyph compound = new Stick();

        for (Glyph glyph : parts) {
            compound.addGlyphSections(glyph, /* linkSections => */
                                      false);
        }

        // Register (a copy of) the parts in the compound itself
        compound.setParts(parts);

        // Compute glyph parameters
        SystemInfo system = sheet.getSystemAtY(compound.getContourBox().y);
        computeGlyphFeatures(system, compound);

        return compound;
    }

    //------------------------//
    // extractNewSystemGlyphs //
    //------------------------//
    /**
     * In the specified system, build new glyphs from unknown sections (sections
     * not linked to a known glyph)
     *
     * @param system the specified system
     */
    public void extractNewSystemGlyphs (SystemInfo system)
    {
        removeSystemInactives(system);
        retrieveSystemGlyphs(system);
    }

    //-------------//
    // insertGlyph //
    //-------------//
    /**
     * Insert a brand new glyph in proper system and lag. It does not check if
     * the glyph has an assigned shape.
     *
     * @param glyph the brand new glyph
     * @return the original glyph as inserted in the glyph lag
     */
    public Glyph insertGlyph (Glyph glyph)
    {
        return insertGlyph(glyph, sheet.getSystemAtY(glyph.getContourBox().y));
    }

    //-------------//
    // insertGlyph //
    //-------------//
    /**
     * Insert a brand new glyph in proper system and lag. It does not check if
     * the glyph has an assigned shape.
     *
     * @param glyph the brand new glyph
     * @param system the containing system, which may be null
     * @return the original glyph as inserted in the glyph lag
     */
    public Glyph insertGlyph (Glyph      glyph,
                              SystemInfo system)
    {
        // Make sure we do have an enclosing system
        if (system == null) {
            system = sheet.getSystemAtY(glyph.getContourBox().y);
        }

        // Get rid of composing parts if any
        if (glyph.getParts() != null) {
            for (Glyph part : glyph.getParts()) {
                part.setPartOf(glyph);
                part.setShape(Shape.NO_LEGAL_SHAPE);
                removeGlyph(part, system, /* cutSections => */
                            false);
            }
        }

        // Record related scale ?
        if (glyph.getInterline() == 0) {
            glyph.setInterline(scale.interline());
        }

        // Insert in lag, which assigns an id to the glyph
        Glyph oldGlyph = vLag.addGlyph(glyph);

        if (oldGlyph != glyph) {
            // Perhaps some members to carry over
            oldGlyph.pullFrom(glyph);
        }

        system.addGlyph(oldGlyph);

        return oldGlyph;
    }

    //-------------//
    // removeGlyph //
    //-------------//
    /**
     * Remove a glyph from the containing lag and the containing system glyph
     * list.
     *
     * @param glyph the glyph to remove
     * @param system the (potential) containing system info, or null
     * @param cutSections true to also cut the link between member sections and
     *                    glyph
     */
    public void removeGlyph (Glyph      glyph,
                             SystemInfo system,
                             boolean    cutSections)
    {
        // Remove from system
        int y = glyph.getContourBox().y;

        if (system == null) {
            system = sheet.getSystemAtY(y);
        }

        if (!system.removeGlyph(glyph)) {
            SystemInfo closest = sheet.getClosestSystem(system, y);

            if (closest != null) {
                if (!closest.removeGlyph(glyph)) {
                    logger.warning(
                        "Cannot find " + glyph + " close to " + system +
                        " closest was " + closest);
                }
            }
        }

        // Remove from lag
        glyph.destroy(cutSections);
    }

    //-----------------------//
    // removeSystemInactives //
    //-----------------------//
    /**
     * On a specified system, look for all inactive glyphs and remove them from
     * its glyphs collection as well as from the containing lag.
     * Purpose is to prepare room for a new glyph extraction
     *
     * @param system the specified system
     */
    public void removeSystemInactives (SystemInfo system)
    {
        for (Glyph glyph : system.getGlyphs()) {
            if (!glyph.isActive()) {
                // Remove from system (& lag)
                removeGlyph(glyph, system, /* cutSections => */
                            false);
            }
        }
    }

    //----------------//
    // retrieveGlyphs //
    //----------------//
    /**
     * Retrieve the new glyphs that can be built in all systems of the sheet
     */
    public void retrieveGlyphs ()
    {
        // Make sure horizontals (such as ledgers) & verticals (such as
        // stems) have been retrieved
        sheet.getHorizontals();

        int nb = 0;

        // Now considerConnection each system area on turn
        for (SystemInfo system : sheet.getSystems()) {
            nb += retrieveSystemGlyphs(system);
        }

        // Report result
        if (logger.isFineEnabled() && (nb > 0)) {
            logger.fine(nb + " glyph" + ((nb > 1) ? "s" : "") + " found");
        } else {
            logger.fine("No glyph found");
        }
    }

    //----------------------//
    // retrieveSystemGlyphs //
    //----------------------//
    /**
     * In a given system area, browse through all sections not assigned to known
     * glyphs, and build new glyphs out of connected sections
     *
     * @param system the system area to process
     * @return the number of glyphs built in this system
     */
    public int retrieveSystemGlyphs (SystemInfo system)
    {
        int               nb = 0;
        Set<GlyphSection> visitedSections = new HashSet<GlyphSection>();

        // Browse the various unrecognized sections
        for (GlyphSection section : system.getVerticalSections()) {
            // Not already visited ?
            if (!section.isKnown() && !visitedSections.contains(section)) {
                // Let's build a new glyph around this starting section
                Glyph glyph = new Stick();
                considerConnection(glyph, section, visitedSections);

                // Compute all its characteristics
                computeGlyphFeatures(system, glyph);

                // And insert this newly built glyph at proper location
                glyph = insertGlyph(glyph, system);
                nb++;
            }
        }

        return nb;
    }

    //--------------------//
    // checkDashIntersect //
    //--------------------//
    private boolean checkDashIntersect (List<?extends Dash> items,
                                        int                 maxItemWidth,
                                        Rectangle           box)
    {
        int startIdx = Dash.getDashIndexAtX(items, box.x - maxItemWidth);

        if (startIdx < items.size()) {
            int stopIdx = Dash.getDashIndexAtX(items, box.x + box.width + 1); // Not sure we need "+1"

            for (Dash item : items.subList(startIdx, stopIdx)) {
                if (item.getContourBox()
                        .intersects(box)) {
                    return true;
                }
            }
        }

        return false;
    }

    //--------------------//
    // checkStemIntersect //
    //--------------------//
    private boolean checkStemIntersect (Collection<Glyph> glyphs,
                                        Glyph             glyph,
                                        boolean           onLeft)
    {
        ///logger.info("checkStemIntersect glyph#" + glyph.getId() + " among" + Glyph.toString(glyphs));
        // Box for searching for a stem
        Rectangle box;

        if (onLeft) {
            box = leftStemBox(glyph.getContourBox());
        } else {
            box = rightStemBox(glyph.getContourBox());
        }

        for (Glyph s : glyphs) {
            // Check bounding box intersection
            if (s.isStem() && s.getContourBox()
                               .intersects(box)) {
                // Check adjacency
                for (GlyphSection section : glyph.getMembers()) {
                    if (onLeft) {
                        for (GlyphSection source : section.getSources()) {
                            if (source.getGlyph() == s) {
                                glyph.setLeftStem(s);

                                return true;
                            }
                        }
                    } else {
                        for (GlyphSection target : section.getTargets()) {
                            if (target.getGlyph() == s) {
                                glyph.setRightStem(s);

                                return true;
                            }
                        }
                    }
                }

                // Check close distance
                Rectangle b = stemBoxOf(s);

                for (GlyphSection section : glyph.getMembers()) {
                    if (section.getContour()
                               .intersects(b.x, b.y, b.width, b.height)) {
                        if (onLeft) {
                            glyph.setLeftStem(s);
                        } else {
                            glyph.setRightStem(s);
                        }

                        //                        logger.fine(
                        //                            "Close distance between stem#" + s.getId() + " & " +
                        //                            glyph);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    //----------------------//
    // computeGlyphFeatures //
    //----------------------//
    /**
     * Compute all the features that will be used to recognize the glyph at hand
     * (it's a mix of moments plus a few other characteristics)
     *
     * @param system the system area which contains the glyph
     * @param glyph the glyph at hand
     */
    private void computeGlyphFeatures (SystemInfo system,
                                       Glyph      glyph)
    {
        // Ordinate (approximate value)
        Rectangle box = glyph.getContourBox();
        int       y = box.y;

        // Interline value ?
        if (glyph.getInterline() == 0) {
            glyph.setInterline(scale.interline());
        }

        // Mass center (which makes sure moments are available)
        SystemPoint centroid = system.getScoreSystem()
                                     .toSystemPoint(glyph.getCentroid());
        Staff       staff = system.getScoreSystem()
                                  .getStaffAt(centroid);

        // Number of connected stems
        int stemNb = 0;

        if (checkStemIntersect(system.getGlyphs(), glyph, /* onLeft => */
                               true)) {
            stemNb++;
        }

        if (checkStemIntersect(system.getGlyphs(), glyph, /* onLeft => */
                               false)) {
            stemNb++;
        }

        glyph.setStemNumber(stemNb);

        // Has a related ledger ?
        glyph.setWithLedger(
            checkDashIntersect(
                system.getLedgers(),
                system.getMaxLedgerWidth(),
                ledgerBox(box)));

        // Vertical position wrt staff
        glyph.setPitchPosition(staff.pitchPositionOf(centroid));
    }

    //--------------------//
    // considerConnection //
    //--------------------//
    private void considerConnection (Glyph             glyph,
                                     GlyphSection      section,
                                     Set<GlyphSection> visitedSections)
    {
        // Check whether this section is suitable to expand the glyph
        if (!section.isKnown() && !visitedSections.contains(section)) {
            visitedSections.add(section);

            glyph.addSection(section, /* link => */
                             true);

            // Add recursively all linked sections in the lag
            //
            // Incoming ones
            for (GlyphSection source : section.getSources()) {
                considerConnection(glyph, source, visitedSections);
            }

            //
            // Outgoing ones
            for (GlyphSection target : section.getTargets()) {
                considerConnection(glyph, target, visitedSections);
            }
        }
    }

    //-----------//
    // ledgerBox //
    //-----------//
    private Rectangle ledgerBox (Rectangle rect)
    {
        int dy = scale.toPixels(constants.ledgerHeighten);

        return new Rectangle(
            rect.x,
            rect.y - dy,
            rect.width,
            rect.height + (2 * dy));
    }

    //-------------//
    // leftStemBox //
    //-------------//
    private Rectangle leftStemBox (Rectangle rect)
    {
        int dx = scale.toPixels(constants.stemWiden);
        int dy = scale.toPixels(constants.stemHeighten);

        return new Rectangle(
            rect.x - dx,
            rect.y - dy,
            2 * dx,
            rect.height + 2 + dy);
    }

    //--------------//
    // rightStemBox //
    //--------------//
    private Rectangle rightStemBox (Rectangle rect)
    {
        int dx = scale.toPixels(constants.stemWiden);
        int dy = scale.toPixels(constants.stemHeighten);

        return new Rectangle(
            (rect.x + rect.width) - dx,
            rect.y - dy,
            2 * dx,
            rect.height + (2 * dy));
    }

    //-----------//
    // stemBoxOf //
    //-----------//
    private Rectangle stemBoxOf (Glyph s)
    {
        int       dx = scale.toPixels(constants.stemWiden);
        int       dy = scale.toPixels(constants.stemHeighten);
        Rectangle rect = s.getContourBox();

        return new Rectangle(
            rect.x - dx,
            rect.y - dy,
            rect.width + (2 * dx),
            rect.height + (2 * dy));
    }

    //~ Inner Classes ----------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
        extends ConstantSet
    {
        /** Box heightening to check intersection with ledger */
        Scale.Fraction ledgerHeighten = new Scale.Fraction(
            0.1,
            "Box heightening to check intersection with ledger");

        /** Box widening to check intersection with stem */
        Scale.Fraction stemWiden = new Scale.Fraction(
            0.1,
            "Box widening to check intersection with stem");

        /** Box heightening to check intersection with stem */
        Scale.Fraction stemHeighten = new Scale.Fraction(
            0.1,
            "Box heightening to check intersection with stem");
    }
}
