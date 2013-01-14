/*
* Copyright (c) 2012 The Broad Institute
* 
* Permission is hereby granted, free of charge, to any person
* obtaining a copy of this software and associated documentation
* files (the "Software"), to deal in the Software without
* restriction, including without limitation the rights to use,
* copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following
* conditions:
* 
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
* OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.sting.utils.baq;

import net.sf.picard.reference.IndexedFastaSequenceFile;
import net.sf.picard.reference.ReferenceSequence;
import net.sf.samtools.CigarElement;
import net.sf.samtools.CigarOperator;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMUtils;
import org.apache.log4j.Logger;
import org.broadinstitute.sting.utils.collections.Pair;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.sam.ReadUtils;

/*
  The topology of the profile HMM:

           /\             /\        /\             /\
           I[1]           I[k-1]    I[k]           I[L]
            ^   \      \    ^    \   ^   \      \   ^
            |    \      \   |     \  |    \      \  |
    M[0]   M[1] -> ... -> M[k-1] -> M[k] -> ... -> M[L]   M[L+1]
                \      \/        \/      \/      /
                 \     /\        /\      /\     /
                       -> D[k-1] -> D[k] ->

   M[0] points to every {M,I}[k] and every {M,I}[k] points M[L+1].

   On input, _ref is the reference sequence and _query is the query
   sequence. Both are sequences of 0/1/2/3/4 where 4 stands for an
   ambiguous residue. iqual is the base quality. c sets the gap open
   probability, gap extension probability and band width.

   On output, state and q are arrays of length l_query. The higher 30
   bits give the reference position the query base is matched to and the
   lower two bits can be 0 (an alignment match) or 1 (an
   insertion). q[i] gives the phred scaled posterior probability of
   state[i] being wrong.
 */
public class BAQ {
    private final static Logger logger = Logger.getLogger(BAQ.class);
    private final static boolean DEBUG = false;

    public enum CalculationMode {
        OFF,                        // don't apply BAQ at all, the default
        CALCULATE_AS_NECESSARY,     // do HMM BAQ calculation on the fly, as necessary, if there's no tag
        RECALCULATE                 // do HMM BAQ calculation on the fly, regardless of whether there's a tag present
    }

    /** these are features that only the walker can override */
    public enum QualityMode {
        ADD_TAG,                    // calculate the BAQ, but write it into the reads as the BAQ tag, leaving QUAL field alone
        OVERWRITE_QUALS,            // overwrite the quality field directly
        DONT_MODIFY                 // do the BAQ, but don't modify the quality scores themselves, just return them in the function.
    }

    public static final String BAQ_TAG = "BQ";

    private static double[] qual2prob = new double[256];
    static {
        for (int i = 0; i < 256; ++i)
            qual2prob[i] = Math.pow(10, -i/10.);
    }

    // Phred scaled now (changed 1/10/2011)
    public static final double DEFAULT_GOP = 40;

    /*  Takes a Phred Scale quality score and returns the error probability.
     *
     *  Quick conversion function to maintain internal structure of BAQ calculation on
     *  probability scale, but take the user entered parameter in phred-scale.
     *
     *  @param x phred scaled score
     *  @return probability of incorrect base call
     */
    private double convertFromPhredScale(double x) { return (Math.pow(10,(-x)/10.));}

    public double cd = -1;      // gap open probability [1e-3]
    private double ce = 0.1;    // gap extension probability [0.1]
	private int cb = 7;         // band width [7]
    private boolean includeClippedBases = false;

    public byte getMinBaseQual() {
        return minBaseQual;
    }

    /**
     * Any bases with Q < MIN_BASE_QUAL are raised up to this base quality
     */
    private byte minBaseQual = 4;

    public double getGapOpenProb() {
        return cd;
    }

    public double getGapExtensionProb() {
        return ce;
    }

    public int getBandWidth() {
        return cb;
    }

    /**
     * Use defaults for everything
     */
    public BAQ() {
        this(DEFAULT_GOP);
    }

    /**
     * Use defaults for everything
     */
    public BAQ(final double gapOpenPenalty) {
        cd = convertFromPhredScale(gapOpenPenalty);
        initializeCachedData();
    }



    /**
     * Create a new HmmGlocal object with specified parameters
     *
     * @param d gap open prob (not phred scaled!).
     * @param e gap extension prob.
     * @param b band width
     * @param minBaseQual All bases with Q < minBaseQual are up'd to this value
     */
	public BAQ(final double d, final double e, final int b, final byte minBaseQual, boolean includeClippedBases) {
		cd = d; ce = e; cb = b;
        this.minBaseQual = minBaseQual;
        this.includeClippedBases = includeClippedBases;
        initializeCachedData();
	}

    private final static double EM = 0.33333333333;
    private final static double EI = 0.25;

    private double[][][] EPSILONS = new double[256][256][SAMUtils.MAX_PHRED_SCORE+1];

    private void initializeCachedData() {
        for ( int i = 0; i < 256; i++ )
            for ( int j = 0; j < 256; j++ )
                for ( int q = 0; q <= SAMUtils.MAX_PHRED_SCORE; q++ ) {
                    EPSILONS[i][j][q] = 1.0;
                }

        for ( char b1 : "ACGTacgt".toCharArray() ) {
            for ( char b2 : "ACGTacgt".toCharArray() ) {
                for ( int q = 0; q <= SAMUtils.MAX_PHRED_SCORE; q++ ) {
                    double qual = qual2prob[q < minBaseQual ? minBaseQual : q];
                    double e = Character.toLowerCase(b1) == Character.toLowerCase(b2) ? 1 - qual : qual * EM;
                    EPSILONS[(byte)b1][(byte)b2][q] = e;
                }
            }
        }
    }

    protected double calcEpsilon( byte ref, byte read, byte qualB ) {
        return EPSILONS[ref][read][qualB];
    }

    // ####################################################################################################
    //
    // NOTE -- THIS CODE IS SYNCHRONIZED WITH CODE IN THE SAMTOOLS REPOSITORY.  CHANGES TO THIS CODE SHOULD BE
    // NOTE -- PUSHED BACK TO HENG LI
    //
    // ####################################################################################################
    public int hmm_glocal(final byte[] ref, final byte[] query, int qstart, int l_query, final byte[] _iqual, int[] state, byte[] q) {
        if ( ref == null ) throw new ReviewedStingException("BUG: ref sequence is null");
        if ( query == null ) throw new ReviewedStingException("BUG: query sequence is null");
        if ( _iqual == null ) throw new ReviewedStingException("BUG: query quality vector is null");
        if ( query.length != _iqual.length ) throw new ReviewedStingException("BUG: read sequence length != qual length");
        if ( l_query < 1 ) throw new ReviewedStingException("BUG: length of query sequence < 0: " + l_query);
        if ( qstart < 0 ) throw new ReviewedStingException("BUG: query sequence start < 0: " + qstart);

        //if ( q != null && q.length != state.length ) throw new ReviewedStingException("BUG: BAQ quality length != read sequence length");
        //if ( state != null && state.length != l_query ) throw new ReviewedStingException("BUG: state length != read sequence length");

		int i, k;

        /*** initialization ***/
		// change coordinates
		final int l_ref = ref.length;

		// set band width
		int bw2, bw = l_ref > l_query? l_ref : l_query;
        if (cb < Math.abs(l_ref - l_query)) {
            bw = Math.abs(l_ref - l_query) + 3;
            //System.out.printf("SC  cb=%d, bw=%d%n", cb, bw);
        }
        if (bw > cb) bw = cb;
		if (bw < Math.abs(l_ref - l_query)) {
            //int bwOld = bw;
            bw = Math.abs(l_ref - l_query);
            //System.out.printf("old bw is %d, new is %d%n", bwOld, bw);
        }
        //System.out.printf("c->bw = %d, bw = %d, l_ref = %d, l_query = %d\n", cb, bw, l_ref, l_query);
		bw2 = bw * 2 + 1;

        // allocate the forward and backward matrices f[][] and b[][] and the scaling array s[]
		double[][] f = new double[l_query+1][bw2*3 + 6];
		double[][] b = new double[l_query+1][bw2*3 + 6];
		double[] s = new double[l_query+2];

		// initialize transition probabilities
		double sM, sI, bM, bI;
		sM = sI = 1. / (2 * l_query + 2);
        bM = (1 - cd) / l_ref; bI = cd / l_ref; // (bM+bI)*l_ref==1

		double[] m = new double[9];
		m[0*3+0] = (1 - cd - cd) * (1 - sM); m[0*3+1] = m[0*3+2] = cd * (1 - sM);
		m[1*3+0] = (1 - ce) * (1 - sI); m[1*3+1] = ce * (1 - sI); m[1*3+2] = 0.;
		m[2*3+0] = 1 - ce; m[2*3+1] = 0.; m[2*3+2] = ce;


		/*** forward ***/
		// f[0]
		f[0][set_u(bw, 0, 0)] = s[0] = 1.;
		{ // f[1]
			double[] fi = f[1];
			double sum;
			int beg = 1, end = l_ref < bw + 1? l_ref : bw + 1, _beg, _end;
			for (k = beg, sum = 0.; k <= end; ++k) {
				int u;
                double e = calcEpsilon(ref[k-1], query[qstart], _iqual[qstart]);
				u = set_u(bw, 1, k);
				fi[u+0] = e * bM; fi[u+1] = EI * bI;
				sum += fi[u] + fi[u+1];
			}
			// rescale
			s[1] = sum;
			_beg = set_u(bw, 1, beg); _end = set_u(bw, 1, end); _end += 2;
			for (k = _beg; k <= _end; ++k) fi[k] /= sum;
		}

		// f[2..l_query]
		for (i = 2; i <= l_query; ++i) {
			double[] fi = f[i], fi1 = f[i-1];
			double sum;
			int beg = 1, end = l_ref, x, _beg, _end;
			byte qyi = query[qstart+i-1];
			x = i - bw; beg = beg > x? beg : x; // band start
			x = i + bw; end = end < x? end : x; // band end
			for (k = beg, sum = 0.; k <= end; ++k) {
				int u, v11, v01, v10;
                double e = calcEpsilon(ref[k-1], qyi, _iqual[qstart+i-1]);
				u = set_u(bw, i, k); v11 = set_u(bw, i-1, k-1); v10 = set_u(bw, i-1, k); v01 = set_u(bw, i, k-1);
				fi[u+0] = e * (m[0] * fi1[v11+0] + m[3] * fi1[v11+1] + m[6] * fi1[v11+2]);
				fi[u+1] = EI * (m[1] * fi1[v10+0] + m[4] * fi1[v10+1]);
				fi[u+2] = m[2] * fi[v01+0] + m[8] * fi[v01+2];
				sum += fi[u] + fi[u+1] + fi[u+2];
				//System.out.println("("+i+","+k+";"+u+"): "+fi[u]+","+fi[u+1]+","+fi[u+2]);
			}
			// rescale
			s[i] = sum;
			_beg = set_u(bw, i, beg); _end = set_u(bw, i, end); _end += 2;
			for (k = _beg, sum = 1./sum; k <= _end; ++k) fi[k] *= sum;
		}
		{ // f[l_query+1]
			double sum;
			for (k = 1, sum = 0.; k <= l_ref; ++k) {
				int u = set_u(bw, l_query, k);
				if (u < 3 || u >= bw2*3+3) continue;
				sum += f[l_query][u+0] * sM + f[l_query][u+1] * sI;
			}
			s[l_query+1] = sum; // the last scaling factor
		}

		/*** backward ***/
		// b[l_query] (b[l_query+1][0]=1 and thus \tilde{b}[][]=1/s[l_query+1]; this is where s[l_query+1] comes from)
		for (k = 1; k <= l_ref; ++k) {
			int u = set_u(bw, l_query, k);
			double[] bi = b[l_query];
			if (u < 3 || u >= bw2*3+3) continue;
			bi[u+0] = sM / s[l_query] / s[l_query+1]; bi[u+1] = sI / s[l_query] / s[l_query+1];
		}
		// b[l_query-1..1]
		for (i = l_query - 1; i >= 1; --i) {
			int beg = 1, end = l_ref, x, _beg, _end;
			double[] bi = b[i], bi1 = b[i+1];
			double y = (i > 1)? 1. : 0.;
			byte qyi1 = query[qstart+i];
			x = i - bw; beg = beg > x? beg : x;
			x = i + bw; end = end < x? end : x;
			for (k = end; k >= beg; --k) {
				int u, v11, v01, v10;
				u = set_u(bw, i, k); v11 = set_u(bw, i+1, k+1); v10 = set_u(bw, i+1, k); v01 = set_u(bw, i, k+1);
                final double e = (k >= l_ref? 0 : calcEpsilon(ref[k], qyi1, _iqual[qstart+i])) * bi1[v11];
                bi[u+0] = e * m[0] + EI * m[1] * bi1[v10+1] + m[2] * bi[v01+2]; // bi1[v11] has been folded into e.
				bi[u+1] = e * m[3] + EI * m[4] * bi1[v10+1];
				bi[u+2] = (e * m[6] + m[8] * bi[v01+2]) * y;
			}
			// rescale
			_beg = set_u(bw, i, beg); _end = set_u(bw, i, end); _end += 2;
			for (k = _beg, y = 1./s[i]; k <= _end; ++k) bi[k] *= y;
		}

 		double pb;
		{ // b[0]
			int beg = 1, end = l_ref < bw + 1? l_ref : bw + 1;
			double sum = 0.;
			for (k = end; k >= beg; --k) {
				int u = set_u(bw, 1, k);
                double e = calcEpsilon(ref[k-1], query[qstart], _iqual[qstart]);
                if (u < 3 || u >= bw2*3+3) continue;
				sum += e * b[1][u+0] * bM + EI * b[1][u+1] * bI;
			}
			pb = b[0][set_u(bw, 0, 0)] = sum / s[0]; // if everything works as is expected, pb == 1.0
		}

        
		/*** MAP ***/
		for (i = 1; i <= l_query; ++i) {
			double sum = 0., max = 0.;
			final double[] fi = f[i], bi = b[i];
			int beg = 1, end = l_ref, x, max_k = -1;
			x = i - bw; beg = beg > x? beg : x;
			x = i + bw; end = end < x? end : x;
			for (k = beg; k <= end; ++k) {
				final int u = set_u(bw, i, k);
				double z;
				sum += (z = fi[u+0] * bi[u+0]); if (z > max) { max = z; max_k = (k-1)<<2 | 0; }
				sum += (z = fi[u+1] * bi[u+1]); if (z > max) { max = z; max_k = (k-1)<<2 | 1; }
			}
			max /= sum; sum *= s[i]; // if everything works as is expected, sum == 1.0
			if (state != null) state[qstart+i-1] = max_k;
			if (q != null) {
				k = (int)(-4.343 * Math.log(1. - max) + .499); // = 10*log10(1-max)
				q[qstart+i-1] = (byte)(k > 100? 99 : (k < minBaseQual ? minBaseQual : k));
			}
			//System.out.println("("+pb+","+sum+")"+" ("+(i-1)+","+(max_k>>2)+","+(max_k&3)+","+max+")");
		}

		return 0;
	}

    // ---------------------------------------------------------------------------------------------------------------
    //
    // Helper routines
    //
    // ---------------------------------------------------------------------------------------------------------------

    /** decode the bit encoded state array values */
    public static boolean stateIsIndel(int state) {
        return (state & 3) != 0;
    }

    /** decode the bit encoded state array values */
    public static int stateAlignedPosition(int state) {
        return state >> 2;
    }

    /**
     * helper routine for hmm_glocal
     *
     * @param b
     * @param i
     * @param k
     * @return
     */
    private static int set_u(final int b, final int i, final int k) {
		int x = i - b;
		x = x > 0 ? x : 0;
		return (k + 1 - x) * 3;
	}

    // ---------------------------------------------------------------------------------------------------------------
    //
    // Actually working with the BAQ tag now
    //
    // ---------------------------------------------------------------------------------------------------------------
    
    /**
     * Get the BAQ attribute from the tag in read.  Returns null if no BAQ tag is present.
     * @param read
     * @return
     */
    public static byte[] getBAQTag(SAMRecord read) {
        String s = read.getStringAttribute(BAQ_TAG);
        return s != null ? s.getBytes() : null;
    }

    public static String encodeBQTag(SAMRecord read, byte[] baq) {
        // Offset to base alignment quality (BAQ), of the same length as the read sequence.
        // At the i-th read base, BAQi = Qi - (BQi - 64) where Qi is the i-th base quality.
        // so BQi = Qi - BAQi + 64
        byte[] bqTag = new byte[baq.length];
        for ( int i = 0; i < bqTag.length; i++) {
            final int bq = (int)read.getBaseQualities()[i] + 64;
            final int baq_i = (int)baq[i];
            final int tag = bq - baq_i;
            // problem with the calculation of the correction factor; this is our problem
            if ( tag < 0 )
                throw new ReviewedStingException("BAQ tag calculation error.  BAQ value above base quality at " + read);
            // the original quality is too high, almost certainly due to using the wrong encoding in the BAM file
            if ( tag > Byte.MAX_VALUE )
                throw new UserException.MisencodedBAM(read, "we encountered an extremely high quality score (" + (int)read.getBaseQualities()[i] + ") with BAQ correction factor of " + baq_i);
            bqTag[i] = (byte)tag;
        }
        return new String(bqTag);
    }

    public static void addBAQTag(SAMRecord read, byte[] baq) {
        read.setAttribute(BAQ_TAG, encodeBQTag(read, baq));
    }


    /**
      * Returns true if the read has a BAQ tag, or false otherwise
      * @param read
      * @return
      */
    public static boolean hasBAQTag(SAMRecord read) {
        return read.getStringAttribute(BAQ_TAG) != null;
    }

    /**
     * Returns a new qual array for read that includes the BAQ adjustment.  Does not support on-the-fly BAQ calculation
     *
     * @param read the SAMRecord to operate on
     * @param overwriteOriginalQuals If true, we replace the original qualities scores in the read with their BAQ'd version
     * @param useRawQualsIfNoBAQTag If useRawQualsIfNoBAQTag is true, then if there's no BAQ annotation we just use the raw quality scores.  Throws IllegalStateException is false and no BAQ tag is present
     * @return
     */
    public static byte[] calcBAQFromTag(SAMRecord read, boolean overwriteOriginalQuals, boolean useRawQualsIfNoBAQTag) {
        byte[] rawQuals = read.getBaseQualities();
        byte[] newQuals = rawQuals;
        byte[] baq = getBAQTag(read);

        if ( baq != null ) {
            // Offset to base alignment quality (BAQ), of the same length as the read sequence.
            // At the i-th read base, BAQi = Qi - (BQi - 64) where Qi is the i-th base quality.
            newQuals = overwriteOriginalQuals ? rawQuals : new byte[rawQuals.length];
            for ( int i = 0; i < rawQuals.length; i++) {
                int rawQual = (int)rawQuals[i];
                int baq_delta = (int)baq[i] - 64;
                int newval =  rawQual - baq_delta;
                if ( newval < 0 )
                    throw new UserException.MalformedBAM(read, "BAQ tag error: the BAQ value is larger than the base quality");
                newQuals[i] = (byte)newval;
            }
        } else if ( ! useRawQualsIfNoBAQTag ) {
            throw new IllegalStateException("Required BAQ tag to be present, but none was on read " + read.getReadName());
        }

        return newQuals;
    }

    /**
     * Returns the BAQ adjusted quality score for this read at this offset.  Does not support on-the-fly BAQ calculation
     *
     * @param read the SAMRecord to operate on
     * @param offset the offset of operate on
     * @param useRawQualsIfNoBAQTag If useRawQualsIfNoBAQTag is true, then if there's no BAQ annotation we just use the raw quality scores.  Throws IllegalStateException is false and no BAQ tag is present
     * @return
     */
    public static byte calcBAQFromTag(SAMRecord read, int offset, boolean useRawQualsIfNoBAQTag) {
        byte rawQual = read.getBaseQualities()[offset];
        byte newQual = rawQual;
        byte[] baq = getBAQTag(read);

        if ( baq != null ) {
            // Offset to base alignment quality (BAQ), of the same length as the read sequence.
            // At the i-th read base, BAQi = Qi - (BQi - 64) where Qi is the i-th base quality.
            int baq_delta = (int)baq[offset] - 64;
            int newval =  rawQual - baq_delta;
            if ( newval < 0 )
                throw new UserException.MalformedBAM(read, "BAQ tag error: the BAQ value is larger than the base quality");
            newQual = (byte)newval;
        
        } else if ( ! useRawQualsIfNoBAQTag ) {
            throw new IllegalStateException("Required BAQ tag to be present, but none was on read " + read.getReadName());
        }

        return newQual;
    }

    public static class BAQCalculationResult {
        public byte[] refBases, rawQuals, readBases, bq;
        public int[] state;

        public BAQCalculationResult(SAMRecord read, byte[] ref) {
            this(read.getBaseQualities(), read.getReadBases(), ref);
        }

        public BAQCalculationResult(byte[] bases, byte[] quals, byte[] ref) {
            // prepares data for calculation
            rawQuals = quals;
            readBases = bases;

            // now actually prepare the data structures, and fire up the hmm
            bq = new byte[rawQuals.length];
            state = new int[rawQuals.length];
            this.refBases = ref;
        }
    }

     public BAQCalculationResult calcBAQFromHMM(SAMRecord read, IndexedFastaSequenceFile refReader) {
        // start is alignment start - band width / 2 - size of first I element, if there is one.  Stop is similar
        int offset = getBandWidth() / 2;
        long readStart = includeClippedBases ? read.getUnclippedStart() : read.getAlignmentStart();
        long start = Math.max(readStart - offset - ReadUtils.getFirstInsertionOffset(read), 0);
        long stop = (includeClippedBases ? read.getUnclippedEnd() : read.getAlignmentEnd()) + offset + ReadUtils.getLastInsertionOffset(read);

        if ( stop > refReader.getSequenceDictionary().getSequence(read.getReferenceName()).getSequenceLength() ) {
            return null;
        } else {
            // now that we have the start and stop, get the reference sequence covering it
            ReferenceSequence refSeq = refReader.getSubsequenceAt(read.getReferenceName(), start, stop);
            return calcBAQFromHMM(read, refSeq.getBases(), (int)(start - readStart));
        }
    }

//    final SimpleTimer total = new SimpleTimer();
//    final SimpleTimer local = new SimpleTimer();
//    int n = 0;
    public BAQCalculationResult calcBAQFromHMM(byte[] ref, byte[] query, byte[] quals, int queryStart, int queryEnd ) {
//        total.restart();
        if ( queryStart < 0 ) throw new ReviewedStingException("BUG: queryStart < 0: " + queryStart);
        if ( queryEnd < 0 ) throw new ReviewedStingException("BUG: queryEnd < 0: " + queryEnd);
        if ( queryEnd < queryStart ) throw new ReviewedStingException("BUG: queryStart < queryEnd : " + queryStart + " end =" + queryEnd);

        // note -- assumes ref is offset from the *CLIPPED* start
        BAQCalculationResult baqResult = new BAQCalculationResult(query, quals, ref);
        int queryLen = queryEnd - queryStart;
//        local.restart();
        hmm_glocal(baqResult.refBases, baqResult.readBases, queryStart, queryLen, baqResult.rawQuals, baqResult.state, baqResult.bq);
//        local.stop();
//        total.stop();
//        if ( n++ % 100000 == 0 )
//            logger.info("n = " + n + ": Total " + total.getElapsedTimeNano() + " local " + local.getElapsedTimeNano());
        return baqResult;
    }


    /**
     * Determine the appropriate start and stop offsets in the reads for the bases given the cigar string
     * @param read
     * @return
     */
    private final Pair<Integer,Integer> calculateQueryRange(SAMRecord read) {
        int queryStart = -1, queryStop = -1;
        int readI = 0;

        // iterate over the cigar elements to determine the start and stop of the read bases for the BAQ calculation
        for ( CigarElement elt : read.getCigar().getCigarElements() ) {
            switch (elt.getOperator()) {
                case N:  return null; // cannot handle these
                case H : case P : case D: break; // ignore pads, hard clips, and deletions
                case I : case S: case M: case EQ: case X:
                    int prev = readI;
                    readI += elt.getLength();
                    if ( includeClippedBases || elt.getOperator() != CigarOperator.S) {
                        if ( queryStart == -1 )
                            queryStart = prev;
                        queryStop = readI;
                    }
                    // in the else case we aren't including soft clipped bases, so we don't update
                    // queryStart or queryStop
                    break;
                default: throw new ReviewedStingException("BUG: Unexpected CIGAR element " + elt + " in read " + read.getReadName());
            }
        }

        if ( queryStop == queryStart ) {
            // this read is completely clipped away, and yet is present in the file for some reason
            // usually they are flagged as non-PF, but it's possible to push them through the BAM
            //System.err.printf("WARNING -- read is completely clipped away: " + read.format());
            return null;
        }

        return new Pair<Integer, Integer>(queryStart, queryStop);
    }

    // we need to pad ref by at least the bandwidth / 2 on either side
    public BAQCalculationResult calcBAQFromHMM(SAMRecord read, byte[] ref, int refOffset) {
        // todo -- need to handle the case where the cigar sum of lengths doesn't cover the whole read
        Pair<Integer, Integer> queryRange = calculateQueryRange(read);
        if ( queryRange == null ) return null; // read has Ns, or is completely clipped away

        int queryStart = queryRange.getFirst();
        int queryEnd = queryRange.getSecond();

        BAQCalculationResult baqResult = calcBAQFromHMM(ref, read.getReadBases(), read.getBaseQualities(), queryStart, queryEnd);

        // cap quals
        int readI = 0, refI = 0;
        for ( CigarElement elt : read.getCigar().getCigarElements() ) {
            int l = elt.getLength();
            switch (elt.getOperator()) {
                case N: // cannot handle these
                    return null;
                case H : case P : // ignore pads and hard clips
                    break;
                case S : refI += l; // move the reference too, in addition to I
                case I :
                    // todo -- is it really the case that we want to treat I and S the same?
                    for ( int i = readI; i < readI + l; i++ ) baqResult.bq[i] = baqResult.rawQuals[i];
                    readI += l;
                    break;
                case D : refI += l; break;
                case M :
                    for (int i = readI; i < readI + l; i++) {
                        int expectedPos = refI - refOffset + (i - readI);
                        baqResult.bq[i] = capBaseByBAQ( baqResult.rawQuals[i], baqResult.bq[i], baqResult.state[i], expectedPos );
                    }
                    readI += l; refI += l;
                    break;
                default:
                    throw new ReviewedStingException("BUG: Unexpected CIGAR element " + elt + " in read " + read.getReadName());
            }
        }
        if ( readI != read.getReadLength() ) // odd cigar string
            System.arraycopy(baqResult.rawQuals, 0, baqResult.bq, 0, baqResult.bq.length);

        return baqResult;
    }

    public byte capBaseByBAQ( byte oq, byte bq, int state, int expectedPos ) {
        byte b;
        boolean isIndel = stateIsIndel(state);
        int pos = stateAlignedPosition(state);
        if ( isIndel || pos != expectedPos ) // we are an indel or we don't align to our best current position
            b = minBaseQual; // just take b = minBaseQuality
        else
            b = bq < oq ? bq : oq;

        return b;
    }

    /**
     * Modifies read in place so that the base quality scores are capped by the BAQ calculation.  Uses the BAQ
     * tag if present already and alwaysRecalculate is false, otherwise fires up the HMM and does the BAQ on the fly
     * using the refReader to obtain the reference bases as needed.
     * 
     * @param read
     * @param refReader
     * @param calculationType
     * @return BQ qualities for use, in case qmode is DONT_MODIFY
     */
    public byte[] baqRead(SAMRecord read, IndexedFastaSequenceFile refReader, CalculationMode calculationType, QualityMode qmode ) {
        if ( DEBUG ) System.out.printf("BAQ %s read %s%n", calculationType, read.getReadName());

        byte[] BAQQuals = read.getBaseQualities();      // in general we are overwriting quals, so just get a pointer to them
        if ( calculationType == CalculationMode.OFF) { // we don't want to do anything
            ; // just fall though
        } else if ( excludeReadFromBAQ(read) ) {
            ; // just fall through
        } else {
            if ( calculationType == CalculationMode.RECALCULATE || ! hasBAQTag(read) ) {
                if ( DEBUG ) System.out.printf("  Calculating BAQ on the fly%n");
                BAQCalculationResult hmmResult = calcBAQFromHMM(read, refReader);
                if ( hmmResult != null ) {
                    switch ( qmode ) {
                        case ADD_TAG:         addBAQTag(read, hmmResult.bq); break;
                        case OVERWRITE_QUALS: System.arraycopy(hmmResult.bq, 0, read.getBaseQualities(), 0, hmmResult.bq.length); break;
                        case DONT_MODIFY:     BAQQuals = hmmResult.bq; break;
                        default:              throw new ReviewedStingException("BUG: unexpected qmode " + qmode);
                    }
                }
            } else if ( qmode == QualityMode.OVERWRITE_QUALS ) { // only makes sense if we are overwriting quals
                if ( DEBUG ) System.out.printf("  Taking BAQ from tag%n");
                // this overwrites the original qualities
                calcBAQFromTag(read, true, false);
            }
        }

        return BAQQuals;
    }

    /**
     * Returns true if we don't think this read is eligible for the BAQ calculation.  Examples include non-PF reads,
     * duplicates, or unmapped reads.  Used by baqRead to determine if a read should fall through the calculation.
     *
     * @param read
     * @return
     */
    public boolean excludeReadFromBAQ(SAMRecord read) {
        // keeping mapped reads, regardless of pairing status, or primary alignment status.
        return read.getReadUnmappedFlag() || read.getReadFailsVendorQualityCheckFlag() || read.getDuplicateReadFlag();
    }
}
