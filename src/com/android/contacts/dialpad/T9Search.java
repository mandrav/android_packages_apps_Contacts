/**
 * 
 */
package com.android.contacts.dialpad;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.provider.MediaStore.Images.Media;

/**
 * @author shade,Danesh
 *
 */
class T9Search {

    //Contact name queries
    private final static String LOWER = "LOWER(" + Contacts.DISPLAY_NAME + ")";
    private final static String PEOPLE_SELECTION = "(" + LOWER + " GLOB ?) AND " + Contacts.HAS_PHONE_NUMBER + " = 1";
    private static final String[] PEOPLE_PROJECTION = new String[] { Contacts._ID, Contacts.DISPLAY_NAME, Contacts.PHOTO_THUMBNAIL_URI};

    //Phone number queries
    private static final String[] PHONE_PROJECTION = new String[] { Phone._ID, Contacts.DISPLAY_NAME, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.PHOTO_THUMBNAIL_URI };
    private static final String PHONE_ID_SELECTION = ContactsContract.Contacts.Data.MIMETYPE + " = ? ";
    private static final String[] PHONE_ID_SELECTION_ARGS = new String[] {ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE};
    private static final String PHONE_SELECTION = Phone.NORMALIZED_NUMBER + " GLOB ? OR " + Phone.NUMBER + " GLOB ?";

    //List sort modes
    private static final int NAME_FIRST = 1;
    private static final int NUMBER_FIRST = 2;
    private static final int DIRECT_NUMBER = 3;
    private Context mContext;
    private int mSortMode;

    //Local variables
    ArrayList<ContactItem> nameResults = new ArrayList<ContactItem>();
    ArrayList<ContactItem> numberResults = new ArrayList<ContactItem>();
    Set<ContactItem> allResults = new LinkedHashSet<ContactItem>();
    String inputNumber;

    public T9Search(Context context) {
        mContext = context;
    }

    public static class T9SearchResult {
        private final ArrayList<ContactItem> mResults;
        private ContactItem mTopContact;
        public T9SearchResult (final ArrayList<ContactItem> results, final Context mContext) {
            mTopContact = new ContactItem();
            mTopContact.name = results.get(0).name;
            mTopContact.number = results.get(0).number;
            try {
                if (results.get(0).photoUri != null)
                mTopContact.photo = Media.getBitmap(mContext.getContentResolver(), results.get(0).photoUri);
            } catch (FileNotFoundException e) {
            } catch (IOException e) {
            }
            this.mResults = results;
            mResults.remove(0);
            Thread pics = new Thread(new Runnable(){
                @Override
                public void run () {
                    for (ContactItem a : results) {
                        try {
                            if (a.photoUri != null)
                            a.photo = Media.getBitmap(mContext.getContentResolver(), a.photoUri);
                        } catch (FileNotFoundException e) {
                        } catch (IOException e) {
                        }
                    }
                }
            });
            pics.start();
        }
        public int getNumResults() {
            return mResults.size()+1;
        }
        public String getTopName() {
            return mTopContact.name;
        }
        public String getTopNumber() {
            return mTopContact.number;
        }
        public Bitmap getTopPhoto() {
            return mTopContact.photo;
        }
        public ArrayList<ContactItem> getResults() {
            return mResults;
        }
    }

    protected static class ContactItem {
        Bitmap photo;
        Uri photoUri;
        String name;
        String number;
        int matchId;
    }

    public T9SearchResult search(String number) {
        nameResults.clear();
        numberResults.clear();
        allResults.clear();
        inputNumber=number.replaceAll( "[^\\d]", "" );
        mSortMode = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(mContext).getString("t9_sort", "1"));

        //Search for matching phone numbers
        Thread phones = new Thread(new Runnable(){
            @Override
            public void run () {
                Cursor c = searchPhones(inputNumber);
                while (c.moveToNext()) {
                    ContactItem temp = new ContactItem();
                    temp.name = c.getString(1);
                    temp.number = c.getString(2);
                    temp.matchId = temp.number.replaceAll( "[^\\d]", "" ).indexOf(inputNumber);
                    if (c.getString(4)!=null)
                      temp.photoUri = Uri.parse(c.getString(4));
                    numberResults.add(temp);
                }
                c.close();
                Collections.sort(numberResults, new CustomComparator());
            }
        });

        //Search for matching contact names
        Thread names = new Thread(new Runnable(){
            @Override
            public void run () {
                Cursor c = searchContacts(inputNumber);
                while (c.moveToNext()) {
                    ContactItem temp = new ContactItem();
                    temp.name = c.getString(1);
                    temp.number = getBestPhone(c.getString(0));
                    temp.matchId = getNameMatchId(temp.name,inputNumber);
                    if (c.getString(2)!=null)
                       temp.photoUri = Uri.parse(c.getString(2));
                    nameResults.add(temp);
                }
                c.close();
                Collections.sort(nameResults, new CustomComparator());
            }
        });

        names.setPriority(Thread.MAX_PRIORITY);
        if (mSortMode != DIRECT_NUMBER) {
            names.start();
        }
        phones.start();
        try {
            names.join();
            phones.join();
        } catch (InterruptedException e) {
            return null;
        }

        if (nameResults.size() > 0 || numberResults.size() > 0) {
            switch (mSortMode) {
            case NAME_FIRST:
                allResults.addAll(nameResults);
                allResults.addAll(numberResults);
                break;
            case NUMBER_FIRST:
            case DIRECT_NUMBER:
                allResults.addAll(numberResults);
                allResults.addAll(nameResults);
            }
            return new T9SearchResult(new ArrayList<ContactItem>(allResults), mContext);
        }
        return null;
    }

    public static class CustomComparator implements Comparator<ContactItem> {
        @Override
        public int compare(ContactItem lhs, ContactItem rhs) {
            return Integer.compare(lhs.matchId,rhs.matchId);
        }
    }

    private static int getNameMatchId(String name, String input) {
        Pattern pattern = Pattern.compile(buildT9ContactQuery(input),Pattern.CASE_INSENSITIVE);
        Matcher m = pattern.matcher(name);
        if (m.find()){
            return m.start();
        }else{
            return name.length();
        }
    }

    private String getBestPhone(String contactId) {
        Uri baseUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, Long.valueOf(contactId));
        Uri dataUri = Uri.withAppendedPath(baseUri, ContactsContract.Contacts.Data.CONTENT_DIRECTORY);
        Cursor c = mContext.getContentResolver().query(dataUri,PHONE_PROJECTION,PHONE_ID_SELECTION,PHONE_ID_SELECTION_ARGS,null);
        if (c != null && c.moveToFirst()) {
                String phone = c.getString(2);
                c.close();
                return phone;
        }
        return null;
    }

    private Cursor searchPhones(String number) {
        String query = null;
        if (mSortMode==DIRECT_NUMBER){
            Uri contactUri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(number));
            return mContext.getContentResolver().query(contactUri, PHONE_PROJECTION, null, null, null);
        }else{
            query = "*" + number + "*";
            return mContext.getContentResolver().query(Phone.CONTENT_URI,
                    PHONE_PROJECTION,
                    PHONE_SELECTION,
                    new String[] {query,query},
                    null);
        }
    }

    private Cursor searchContacts(String number) {
        String matcher = buildT9ContactQuery(number);
        return mContext.getContentResolver().query(Contacts.CONTENT_URI, 
                PEOPLE_PROJECTION,
                PEOPLE_SELECTION,
                new String[] { "*" + matcher + "*" },
                null);
    }

    private static String buildT9ContactQuery(String number) {
        StringBuilder sb = new StringBuilder();
        if (number != null) {
            for (int i = 0; i < number.length(); i++) {
                char key = number.charAt(i);
                sb.append(numberToRegex(key));
            }
        }
        return sb.toString();
    }

    private static String numberToRegex(char c) {
        switch (c) {
        case '2':
            return "[2abc]";
        case '3':
            return "[3def]";
        case '4':
            return "[4ghi]";
        case '5':
            return "[5jkl]";
        case '6':
            return "[6mno]";
        case '7':
            return "[7pqrs]";
        case '8':
            return "[8tuv]";
        case '9':
            return "[9wxyz]";
        case '*':
            return "?";
        default:
            return String.valueOf(c);
        }
    }
}