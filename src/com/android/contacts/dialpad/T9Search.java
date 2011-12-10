package com.android.contacts.dialpad;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.telephony.PhoneNumberUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.QuickContactBadge;
import android.widget.TextView;

import com.android.contacts.ContactPhotoManager;
import com.android.contacts.R;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author shade, Danesh, pawitp
 */
class T9Search {

    // List sort modes
    private static final int NAME_FIRST = 1;
    private static final int NUMBER_FIRST = 2;
    private static final int DIRECT_NUMBER = 3;

    // Phone number queries
    private static final String[] PHONE_PROJECTION = new String[] {Phone.NUMBER, Phone.CONTACT_ID, Phone.IS_SUPER_PRIMARY};
    private static final String PHONE_ID_SELECTION = Contacts.Data.MIMETYPE + " = ? ";
    private static final String[] PHONE_ID_SELECTION_ARGS = new String[] {Phone.CONTENT_ITEM_TYPE};
    private static final String PHONE_SORT = Phone.CONTACT_ID + " ASC";
    private static final String[] CONTACT_PROJECTION = new String[] {Contacts._ID, Contacts.DISPLAY_NAME, Contacts.TIMES_CONTACTED, Contacts.PHOTO_THUMBNAIL_URI};
    private static final String CONTACT_QUERY = Contacts.HAS_PHONE_NUMBER + " > 0";
    private static final String CONTACT_SORT = Contacts._ID + " ASC";

    // Local variables
    private Context mContext;
    private int mSortMode;
    private ArrayList<ContactItem> nameResults = new ArrayList<ContactItem>();
    private ArrayList<ContactItem> numberResults = new ArrayList<ContactItem>();
    private Set<ContactItem> allResults = new LinkedHashSet<ContactItem>();
    private ArrayList<ContactItem> contacts = new ArrayList<ContactItem>();
    private static Pattern removeNonDigits = Pattern.compile("[^\\d]");

    public T9Search(Context context) {
        mContext = context;
        getAll();
    }

    private void getAll() {
        initT9Map();
        Cursor contact = mContext.getContentResolver().query(Contacts.CONTENT_URI, CONTACT_PROJECTION, CONTACT_QUERY, null, CONTACT_SORT);
        Cursor phone = mContext.getContentResolver().query(Phone.CONTENT_URI, PHONE_PROJECTION, PHONE_ID_SELECTION, PHONE_ID_SELECTION_ARGS, PHONE_SORT);
        phone.moveToFirst();

        while (contact.moveToNext()) {
            long contactId = contact.getLong(0);

            if (phone.isAfterLast()) {
                break;
            }

            while (phone.getLong(1) == contactId) {
                String num = phone.getString(0);
                ContactItem contactInfo = new ContactItem();
                contactInfo.id = contactId;
                contactInfo.name = contact.getString(1);
                contactInfo.number = PhoneNumberUtils.formatNumber(num);
                contactInfo.normalNumber = removeNonDigits.matcher(num).replaceAll("");
                contactInfo.normalName = nameToNumber(contact.getString(1));
                contactInfo.timesContacted = contact.getInt(2);
                contactInfo.isSuperPrimary = phone.getInt(2) > 0;

                if (!contact.isNull(3))
                    contactInfo.photo = Uri.parse(contact.getString(3));

                contacts.add(contactInfo);

                if (!phone.moveToNext()) {
                    break;
                }
            }
        }
        contact.close();
        phone.close();
    }

    public static class T9SearchResult {

        private final ArrayList<ContactItem> mResults;
        private ContactItem mTopContact = new ContactItem();

        public T9SearchResult (final ArrayList<ContactItem> results, final Context mContext) {
            mTopContact = results.get(0);
            mResults = results;
            mResults.remove(0);
        }

        public int getNumResults() {
            return mResults.size() + 1;
        }

        public ContactItem getTopContact() {
            return mTopContact;
        }

        public ArrayList<ContactItem> getResults() {
            return mResults;
        }
    }

    public static class ContactItem {
        Uri photo;
        String name;
        String number;
        String normalNumber;
        String normalName;
        int timesContacted;
        int matchId;
        long id;
        boolean isSuperPrimary;
    }

    public T9SearchResult search(String number) {
        nameResults.clear();
        numberResults.clear();
        allResults.clear();
        number = removeNonDigits.matcher(number).replaceAll("");
        int pos = 0;
        mSortMode = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(mContext).getString("t9_sort", "1"));

        // Go through each contact
        for (ContactItem item : contacts) {
            pos = item.normalNumber.indexOf(number);
            if (pos != -1) {
                item.matchId = pos;
                numberResults.add(item);
            }
            pos = item.normalName.indexOf(number);
            if (pos != -1) {
                int last_space = item.normalName.lastIndexOf("0", pos);
                if (last_space == -1) {
                    last_space = 0;
                }

                item.matchId = pos - last_space;
                nameResults.add(item);
            }
        }
        Collections.sort(numberResults, new CustomComparator());
        Collections.sort(nameResults, new CustomComparator());
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
            int ret = Integer.compare(lhs.matchId, rhs.matchId);
            if (ret == 0) ret = Integer.compare(rhs.timesContacted, lhs.timesContacted);
            if (ret == 0) ret = Boolean.compare(rhs.isSuperPrimary, lhs.isSuperPrimary);
            return ret;
        }
    }

    private void initT9Map() {
        int rc = 0;
        for (String item : mContext.getResources().getStringArray(R.array.t9_map)) {
            int cc = 0;
            T9_MAP[rc] = new char[item.length()];
            for (char ch : item.toCharArray()) {
                T9_MAP[rc][cc] = ch;
                cc++;
            }
            rc++;
        }
    }

    private static char[][] T9_MAP = new char[10][];

    private static String nameToNumber(String name) {
        StringBuilder sb = new StringBuilder();
        int len = name.length();
        for (int i = 0; i < len; i++) {
            boolean matched = false;
            char ch = Character.toLowerCase(name.charAt(i));
            for (char[] row : T9_MAP) {
                for (char a : row) {
                    if (ch == a) {
                        matched = true;
                        sb.append(row[0]);
                        break;
                    }
                }
                if (matched) {
                    break;
                }
            }
            if (!matched) {
                sb.append(T9_MAP[0][0]);
            }
        }
        return sb.toString();
    }

    protected static class T9Adapter extends ArrayAdapter<ContactItem> {

        private ArrayList<ContactItem> items;
        private LayoutInflater menuInflate;
        private ContactPhotoManager mPhotoLoader;

        public T9Adapter(Context context, int textViewResourceId, ArrayList<ContactItem> items, LayoutInflater menuInflate, ContactPhotoManager photoLoader) {
            super(context, textViewResourceId, items);
            this.items = items;
            this.menuInflate = menuInflate;
            mPhotoLoader = photoLoader;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;

            if (convertView == null) {
                convertView = menuInflate.inflate(R.layout.row, null);
                holder = new ViewHolder();
                holder.name = (TextView) convertView.findViewById(R.id.rowName);
                holder.number = (TextView) convertView.findViewById(R.id.rowNumber);
                holder.icon = (QuickContactBadge) convertView.findViewById(R.id.rowBadge);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            ContactItem o = items.get(position);
            holder.name.setText(o.name);
            holder.number.setText(o.number);

            if (o.photo != null)
                mPhotoLoader.loadPhoto(holder.icon, o.photo, false, true);

            holder.icon.assignContactFromPhone(o.number, true);
            return convertView;
        }

        static class ViewHolder {
            TextView name;
            TextView number;
            QuickContactBadge icon;
        }

    }

}